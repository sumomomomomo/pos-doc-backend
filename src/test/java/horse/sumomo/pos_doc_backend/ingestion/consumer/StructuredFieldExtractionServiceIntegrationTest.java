package horse.sumomo.pos_doc_backend.ingestion.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;

import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.utility.DockerImageName;

import horse.sumomo.pos_doc_backend.ingestion.application.ExtractionBackoff;
import horse.sumomo.pos_doc_backend.ingestion.testsupport.SyntheticPdfFactory;
import horse.sumomo.pos_doc_backend.infrastructure.minio.MinioObjectStorage;
import horse.sumomo.pos_doc_backend.ocr.api.LlamaCppOcrProperties;
import horse.sumomo.pos_doc_backend.ocr.application.FieldExtractionPrompts;
import horse.sumomo.pos_doc_backend.ocr.client.LlamaCppOcrClient;
import horse.sumomo.pos_doc_backend.ocr.model.OcrResult;
import horse.sumomo.pos_doc_backend.ocr.service.OcrException;
import horse.sumomo.pos_doc_backend.ocr.testsupport.OcrHttpStub;
import horse.sumomo.pos_doc_backend.rendering.service.RenderingException;
import horse.sumomo.pos_doc_backend.rendering.model.RenderedFirstPage;
import horse.sumomo.pos_doc_backend.rendering.application.FirstPageRenderPreparationService;
import horse.sumomo.pos_doc_backend.rendering.application.DocumentRenderSourceService;
import horse.sumomo.pos_doc_backend.rendering.service.PdfFirstPageRenderer;
import horse.sumomo.pos_doc_backend.rendering.service.StoredPdfMaterializer;

/**
 * Workflow-level integration tests for {@link StructuredFieldExtractionService}
 * against a real temporary SQLite database, a real test MinIO, the real PDFBox
 * renderer (synthetic PDFs), and an ephemeral llama.cpp HTTP stub.
 *
 * <p>The workflow is invoked directly (no RabbitMQ), so these tests deterministically
 * assert exact OCR request counts, candidate selection, durable outcomes, business
 * field application, retry policy, render-once reuse, and idempotent redelivery.
 */
@SpringBootTest(properties = {
		"app.messaging.outbox.enabled=false",
		"app.ingestion.consumer.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StructuredFieldExtractionServiceIntegrationTest {

	private static final String TEST_BUCKET = "pos-documents-field-wf-test";
	private static final DockerImageName MINIO_IMAGE =
			DockerImageName.parse("cgr.dev/chainguard/minio:latest").asCompatibleSubstituteFor("minio/minio");
	private static final String MODEL = "/models/dotsmocr-1.8b-q8_0.gguf";

	private static MinIOContainer minio;
	private static MinioClient adminClient;
	private static OcrHttpStub ocrStub;
	private static StubOcrClient stubOcrClient;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Autowired
	private StructuredFieldExtractionService workflow;

	@Autowired
	private MinioObjectStorage storage;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private FirstPageRenderPreparationService renderService;

	@DynamicPropertySource
	static void containerProperties(DynamicPropertyRegistry registry) throws Exception {
		minio = new MinIOContainer(MINIO_IMAGE)
				.withUserName("field-wf-access-key")
				.withPassword("field-wf-secret-change-me");
		minio.start();
		adminClient = MinioClient.builder()
				.endpoint(minio.getS3URL())
				.credentials(minio.getUserName(), minio.getPassword())
				.build();
		adminClient.makeBucket(MakeBucketArgs.builder().bucket(TEST_BUCKET).build());

		ocrStub = new OcrHttpStub("SYNTHETIC OCR TEXT", 200, "application/json");

		registry.add("storage.minio.endpoint", minio::getS3URL);
		registry.add("storage.minio.access-key", minio::getUserName);
		registry.add("storage.minio.secret-key", minio::getPassword);
		registry.add("storage.minio.bucket", () -> TEST_BUCKET);
		registry.add("app.ocr.llama-cpp.server-origin", ocrStub::getServerOrigin);

		Path dbFile = Files.createTempFile("pos-doc-field-extract-wf-test", ".db");
		dbFile.toFile().deleteOnExit();
		registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbFile.toAbsolutePath());
	}

	@AfterAll
	static void stopContainers() throws Exception {
		if (ocrStub != null) {
			ocrStub.close();
		}
		if (minio != null && minio.isRunning()) {
			minio.stop();
		}
		if (adminClient != null) {
			adminClient.close();
		}
	}

	@BeforeEach
	void resetStub() {
		ocrStub.reset();
	}

	private record Setup(UUID recordId, UUID jobId, List<UUID> documentIds) {
	}

	/**
	 * Creates a PROCESSING (not deleted) record with the given business fields and
	 * the given PDFs (uploaded to MinIO as synthetic PDFs), plus a job.
	 */
	private Setup createRecord(List<String> pdfFilenames, String policyholder, String consultant, String createDate)
			throws Exception {
		UUID recordId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID archiveStorageId = UUID.randomUUID();
		long now = System.currentTimeMillis();

		byte[] dummyZip = new byte[] { 0x50, 0x4B, 0x03, 0x04 };
		String archiveKey = "archives/" + recordId + "/archive.zip";
		try (var in = new ByteArrayInputStream(dummyZip)) {
			this.storage.put(archiveKey, in, dummyZip.length, "application/zip");
		}
		this.jdbc.update(
				"INSERT INTO storage_object (id, object_key, original_filename, content_type, byte_size, sha256, "
						+ "created_at_epoch_ms) VALUES (?,?,?,?,?,?,?)",
				archiveStorageId.toString(), archiveKey, "archive.zip", "application/zip", dummyZip.length,
				sha256Hex(dummyZip), now);
		this.jdbc.update(
				"INSERT INTO pos_record (id, source_archive_id, status, uploaded_by, uploaded_at_epoch_ms, "
						+ "updated_at_epoch_ms, version, policyholder_name, consultant_name, policy_create_date) "
						+ "VALUES (?,?,?,?,?,?,?,?,?,?)",
				recordId.toString(), archiveStorageId.toString(), "PROCESSING", "test-user", now, now, 1,
				policyholder, consultant, createDate);

		List<UUID> docIds = new ArrayList<>();
		for (int i = 0; i < pdfFilenames.size(); i++) {
			byte[] pdf = SyntheticPdfFactory.createPdf("Doc " + i);
			String pdfKey = "documents/" + recordId + "/" + i + ".pdf";
			try (var in = new ByteArrayInputStream(pdf)) {
				this.storage.put(pdfKey, in, pdf.length, "application/pdf");
			}
			UUID docId = UUID.randomUUID();
			UUID docStorageId = UUID.randomUUID();
			this.jdbc.update(
					"INSERT INTO storage_object (id, object_key, original_filename, content_type, byte_size, "
							+ "sha256, created_at_epoch_ms) VALUES (?,?,?,?,?,?,?)",
					docStorageId.toString(), pdfKey, pdfFilenames.get(i), "application/pdf", pdf.length,
					sha256Hex(pdf), now);
			this.jdbc.update(
					"INSERT INTO pos_document (id, pos_record_id, storage_object_id, sequence_number, "
							+ "document_type, processing_status) VALUES (?,?,?,?,?,?)",
					docId.toString(), recordId.toString(), docStorageId.toString(), i, "OTHER", "PENDING");
			docIds.add(docId);
		}

		this.jdbc.update(
				"INSERT INTO ingestion_job (id, pos_record_id, status, attempt_count, created_at_epoch_ms, "
						+ "version) VALUES (?,?,?,?,?,?)",
				jobId.toString(), recordId.toString(), "RUNNING", 1, now, 1);

		return new Setup(recordId, jobId, docIds);
	}

	// ---- happy path ----

	@Test
	void twoPdfHappyPathCandidateCompletedOtherSkippedThreeRequests() throws Exception {
		Setup s = createRecord(List.of("documents/LAPPe.pdf", "documents/other.pdf"), null, null, null);
		ocrStub.enqueueResponse("Charlie Henry", 200, "application/json");
		ocrStub.enqueueResponse("John Davidson", 200, "application/json");
		ocrStub.enqueueResponse("26-Jul-2026", 200, "application/json");

		int renderBefore = renderCount();
		this.workflow.runFieldExtraction(s.recordId(), s.jobId());
		int renderDelta = renderCount() - renderBefore;

		// Exactly 3 OCR requests (one per unresolved field).
		assertEquals(3, ocrStub.getRequestCount());
		// The candidate's first page is rendered once (reused for all 3 calls).
		assertEquals(1, renderDelta, "candidate must be rendered exactly once");

		// Candidate (LAPPe.pdf, seq 0) COMPLETED; non-candidate (seq 1) SKIPPED.
		assertEquals("COMPLETED", docStatus(s.documentIds().get(0)));
		assertEquals("SKIPPED", docStatus(s.documentIds().get(1)));

		// Three durable outcomes for the candidate at prompt version 2.
		Integer outcomeCount = this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM pos_field_extraction WHERE document_id = ? AND prompt_version = 2",
				Integer.class, s.documentIds().get(0).toString());
		assertEquals(3, outcomeCount);

		// Business fields applied (canonical values).
		assertEquals("Charlie Henry", this.jdbc.queryForObject(
				"SELECT policyholder_name FROM pos_record WHERE id = ?", String.class, s.recordId().toString()));
		assertEquals("John Davidson", this.jdbc.queryForObject(
				"SELECT consultant_name FROM pos_record WHERE id = ?", String.class, s.recordId().toString()));
		assertEquals("2026-07-26", this.jdbc.queryForObject(
				"SELECT policy_create_date FROM pos_record WHERE id = ?", String.class, s.recordId().toString()));

		// Job COMPLETED, record REVIEW_REQUIRED.
		assertEquals("COMPLETED", this.jdbc.queryForObject("SELECT status FROM ingestion_job WHERE id = ?",
				String.class, s.jobId().toString()));
		assertEquals("REVIEW_REQUIRED", this.jdbc.queryForObject("SELECT status FROM pos_record WHERE id = ?",
				String.class, s.recordId().toString()));
	}

	@Test
	void threeFieldPromptsAreSentInFixedOrderAndReuseTheSameRenderedPng() throws Exception {
		Setup s = createRecord(List.of("documents/LAPPe.pdf"), null, null, null);
		ocrStub.enqueueResponse("Charlie Henry", 200, "application/json");
		ocrStub.enqueueResponse("John Davidson", 200, "application/json");
		ocrStub.enqueueResponse("26-Jul-2026", 200, "application/json");

		this.workflow.runFieldExtraction(s.recordId(), s.jobId());

		List<OcrHttpStub.RecordedRequest> requests = ocrStub.getRequests();
		assertEquals(3, requests.size());
		assertEquals(FieldExtractionPrompts.POLICYHOLDER_NAME, promptOf(requests.get(0)));
		assertEquals(FieldExtractionPrompts.CONSULTANT_NAME, promptOf(requests.get(1)));
		assertEquals(FieldExtractionPrompts.POLICY_CREATE_DATE, promptOf(requests.get(2)));

		// All three requests carry the identical rendered PNG (reused, not re-rendered).
		String png0 = pngOf(requests.get(0));
		assertEquals(png0, pngOf(requests.get(1)));
		assertEquals(png0, pngOf(requests.get(2)));
		assertFalse(png0.isEmpty());
	}

	@Test
	void singlePdfIsTheCandidate() throws Exception {
		Setup s = createRecord(List.of("documents/only.pdf"), null, null, null);
		ocrStub.enqueueResponse("Charlie Henry", 200, "application/json");
		ocrStub.enqueueResponse("John Davidson", 200, "application/json");
		ocrStub.enqueueResponse("26-Jul-2026", 200, "application/json");

		this.workflow.runFieldExtraction(s.recordId(), s.jobId());

		assertEquals(3, ocrStub.getRequestCount());
		assertEquals("COMPLETED", docStatus(s.documentIds().get(0)));
		assertEquals("COMPLETED", this.jdbc.queryForObject("SELECT status FROM ingestion_job WHERE id = ?",
				String.class, s.jobId().toString()));
	}

	@Test
	void moreThanTenPdfsFallsBackToFirstPdf() throws Exception {
		List<String> names = new ArrayList<>();
		for (int i = 0; i < 11; i++) {
			names.add(i == 10 ? "documents/lappe.pdf" : "documents/f" + i + ".pdf");
		}
		Setup s = createRecord(names, null, null, null);
		ocrStub.enqueueResponse("Charlie Henry", 200, "application/json");
		ocrStub.enqueueResponse("John Davidson", 200, "application/json");
		ocrStub.enqueueResponse("26-Jul-2026", 200, "application/json");

		this.workflow.runFieldExtraction(s.recordId(), s.jobId());

		// More than 10 PDFs and no case-sensitive LAPPe.pdf: the fallback is the first 10.
		// The first candidate (seq 0) resolves all fields; the rest are not needed; the
		// lowercase "lappe.pdf" decoy (seq 10) is never a candidate.
		assertEquals("COMPLETED", docStatus(s.documentIds().get(0)));
		for (int i = 1; i < 11; i++) {
			assertEquals("SKIPPED", docStatus(s.documentIds().get(i)));
		}
		assertEquals(3, ocrStub.getRequestCount());
	}

	@Test
	void withoutLappeFallsBackToFirstPdfWhichIsProcessed() throws Exception {
		// No case-sensitive LAPPe.pdf: the fallback selects the first up to 10 PDFs
		// (all three here), and the first candidate is processed.
		Setup s = createRecord(List.of("documents/a.pdf", "documents/b.pdf", "documents/c.pdf"), null, null, null);
		ocrStub.enqueueResponse("Charlie Henry", 200, "application/json");
		ocrStub.enqueueResponse("John Davidson", 200, "application/json");
		ocrStub.enqueueResponse("26-Jul-2026", 200, "application/json");

		this.workflow.runFieldExtraction(s.recordId(), s.jobId());

		// The first candidate (seq 0) is processed (3 requests); the rest are not needed.
		assertEquals(3, ocrStub.getRequestCount());
		assertEquals("COMPLETED", docStatus(s.documentIds().get(0)));
		assertEquals("SKIPPED", docStatus(s.documentIds().get(1)));
		assertEquals("SKIPPED", docStatus(s.documentIds().get(2)));
		// Fields resolved from the first candidate.
		assertEquals("Charlie Henry", this.jdbc.queryForObject(
				"SELECT policyholder_name FROM pos_record WHERE id = ?", String.class, s.recordId().toString()));
		assertEquals("COMPLETED", this.jdbc.queryForObject("SELECT status FROM ingestion_job WHERE id = ?",
				String.class, s.jobId().toString()));
	}

	@Test
	void unknownForAllFieldsStillCompletesWithNullFields() throws Exception {
		Setup s = createRecord(List.of("documents/LAPPe.pdf", "documents/other.pdf"), null, null, null);
		// Every request returns UNKNOWN.
		ocrStub.setNextResponse("UNKNOWN", 200, "application/json");

		this.workflow.runFieldExtraction(s.recordId(), s.jobId());

		assertEquals(3, ocrStub.getRequestCount());
		assertEquals("COMPLETED", docStatus(s.documentIds().get(0)));
		assertEquals("SKIPPED", docStatus(s.documentIds().get(1)));

		// Three durable UNKNOWN outcomes, no value.
		Integer unknownCount = this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM pos_field_extraction WHERE document_id = ? AND outcome = 'UNKNOWN'",
				Integer.class, s.documentIds().get(0).toString());
		assertEquals(3, unknownCount);

		// Business fields remain null; job completes; record REVIEW_REQUIRED.
		assertEquals(null, this.jdbc.queryForObject("SELECT policyholder_name FROM pos_record WHERE id = ?",
				String.class, s.recordId().toString()));
		assertEquals("COMPLETED", this.jdbc.queryForObject("SELECT status FROM ingestion_job WHERE id = ?",
				String.class, s.jobId().toString()));
		assertEquals("REVIEW_REQUIRED", this.jdbc.queryForObject("SELECT status FROM pos_record WHERE id = ?",
				String.class, s.recordId().toString()));
	}

	// ---- retry policy (exact request counts) ----

	@Test
	void retryable503TwiceThenSuccessUsesExactlyThreeRequests() throws Exception {
		// Only the policyholder is null; consultant and date are already resolved.
		Setup s = createRecord(List.of("documents/LAPPe.pdf"), null, "John Davidson", "2026-01-01");
		ocrStub.enqueueResponse("x", 503, "application/json");
		ocrStub.enqueueResponse("x", 503, "application/json");
		ocrStub.enqueueResponse("Charlie Henry", 200, "application/json");

		this.workflow.runFieldExtraction(s.recordId(), s.jobId());

		assertEquals(3, ocrStub.getRequestCount(), "503, 503, success = exactly 3 requests for the field");
		assertEquals("Charlie Henry", this.jdbc.queryForObject(
				"SELECT policyholder_name FROM pos_record WHERE id = ?", String.class, s.recordId().toString()));
		Integer attempts = this.jdbc.queryForObject(
				"SELECT attempt_count FROM pos_field_extraction WHERE document_id = ? AND field_name = "
						+ "'POLICYHOLDER_NAME'",
				Integer.class, s.documentIds().get(0).toString());
		assertEquals(3, attempts);
	}

	@Test
	void retryable503ThreeTimesThenFailedAfterThreeRequests() throws Exception {
		Setup s = createRecord(List.of("documents/LAPPe.pdf"), null, "John Davidson", "2026-01-01");
		ocrStub.enqueueResponse("x", 503, "application/json");
		ocrStub.enqueueResponse("x", 503, "application/json");
		ocrStub.enqueueResponse("x", 503, "application/json");

		this.workflow.runFieldExtraction(s.recordId(), s.jobId());

		assertEquals(3, ocrStub.getRequestCount(), "503, 503, 503 = exactly 3 requests, then FAILED");
		String outcome = this.jdbc.queryForObject(
				"SELECT outcome FROM pos_field_extraction WHERE document_id = ? AND field_name = 'POLICYHOLDER_NAME'",
				String.class, s.documentIds().get(0).toString());
		assertEquals("FAILED", outcome);
		// The field stays null (best-effort) but the job still completes.
		assertEquals(null, this.jdbc.queryForObject("SELECT policyholder_name FROM pos_record WHERE id = ?",
				String.class, s.recordId().toString()));
		assertEquals("COMPLETED", this.jdbc.queryForObject("SELECT status FROM ingestion_job WHERE id = ?",
				String.class, s.jobId().toString()));
	}

	@Test
	void nonRetryable400UsesExactlyOneRequest() throws Exception {
		Setup s = createRecord(List.of("documents/LAPPe.pdf"), null, "John Davidson", "2026-01-01");
		ocrStub.enqueueResponse("x", 400, "application/json");

		this.workflow.runFieldExtraction(s.recordId(), s.jobId());

		assertEquals(1, ocrStub.getRequestCount(), "non-retryable 400 = exactly 1 request");
		String outcome = this.jdbc.queryForObject(
				"SELECT outcome FROM pos_field_extraction WHERE document_id = ? AND field_name = 'POLICYHOLDER_NAME'",
				String.class, s.documentIds().get(0).toString());
		assertEquals("FAILED", outcome);
	}

	@Test
	void nonRetryable404UsesExactlyOneRequest() throws Exception {
		Setup s = createRecord(List.of("documents/LAPPe.pdf"), null, "John Davidson", "2026-01-01");
		ocrStub.enqueueResponse("x", 404, "application/json");

		this.workflow.runFieldExtraction(s.recordId(), s.jobId());

		assertEquals(1, ocrStub.getRequestCount());
		assertEquals("FAILED", this.jdbc.queryForObject(
				"SELECT outcome FROM pos_field_extraction WHERE document_id = ? AND field_name = 'POLICYHOLDER_NAME'",
				String.class, s.documentIds().get(0).toString()));
	}

	@Test
	void nonRetryable401UsesExactlyOneRequest() throws Exception {
		Setup s = createRecord(List.of("documents/LAPPe.pdf"), null, "John Davidson", "2026-01-01");
		ocrStub.enqueueResponse("x", 401, "application/json");

		this.workflow.runFieldExtraction(s.recordId(), s.jobId());

		assertEquals(1, ocrStub.getRequestCount());
		assertEquals("FAILED", this.jdbc.queryForObject(
				"SELECT outcome FROM pos_field_extraction WHERE document_id = ? AND field_name = 'POLICYHOLDER_NAME'",
				String.class, s.documentIds().get(0).toString()));
	}

	// ---- case-sensitive candidate selection ----

	@Test
	void caseSensitiveLappeMatchIgnoresLowercaseVariant() throws Exception {
		// Two basenames differ only by case. Only the exact "LAPPe.pdf" is a candidate.
		Setup s = createRecord(List.of("documents/LAPPe.pdf", "documents/lappe.pdf"), null, null, null);
		ocrStub.enqueueResponse("Charlie Henry", 200, "application/json");
		ocrStub.enqueueResponse("John Davidson", 200, "application/json");
		ocrStub.enqueueResponse("26-Jul-2026", 200, "application/json");

		this.workflow.runFieldExtraction(s.recordId(), s.jobId());

		// Only the case-sensitive LAPPe.pdf (seq 0) is the candidate; the lowercase variant (seq 1) is not.
		assertEquals("COMPLETED", docStatus(s.documentIds().get(0)));
		assertEquals("SKIPPED", docStatus(s.documentIds().get(1)));
		assertEquals(3, ocrStub.getRequestCount());
	}

	@Test
	void caseSensitiveLappeMatchIsPreferredEvenWhenOverTen() throws Exception {
		// 11 PDFs with a case-sensitive LAPPe.pdf at seq 10: the match takes priority over the
		// first-10 fallback, so only seq 10 is the candidate.
		List<String> names = new ArrayList<>();
		for (int i = 0; i < 11; i++) {
			names.add(i == 10 ? "documents/LAPPe.pdf" : "documents/f" + i + ".pdf");
		}
		Setup s = createRecord(names, null, null, null);
		ocrStub.enqueueResponse("Charlie Henry", 200, "application/json");
		ocrStub.enqueueResponse("John Davidson", 200, "application/json");
		ocrStub.enqueueResponse("26-Jul-2026", 200, "application/json");

		this.workflow.runFieldExtraction(s.recordId(), s.jobId());

		// Only the LAPPe.pdf (seq 10) is the candidate; the other ten are not.
		assertEquals("COMPLETED", docStatus(s.documentIds().get(10)));
		for (int i = 0; i < 10; i++) {
			assertEquals("SKIPPED", docStatus(s.documentIds().get(i)));
		}
		assertEquals(3, ocrStub.getRequestCount());
	}

	// ---- multi-candidate sequential processing ----

	@Test
	void fieldsResolvingAcrossDifferentPdfs() throws Exception {
		Setup s = createRecord(List.of("documents/LAPPe.pdf", "documents/LAPPe.pdf"), null, null, null);
		// The workflow processes every still-unresolved field for each candidate (the
		// already-resolved set is fixed at the start of a candidate). Request order:
		//   cand0: policyholder -> "Charlie Henry" (resolved); consultant -> "UNKNOWN"; date -> "UNKNOWN"
		//   cand1 (policyholder already resolved, skipped): consultant -> "John Davidson";
		//          date -> "26-Jul-2026" (resolved)
		ocrStub.enqueueResponse("Charlie Henry", 200, "application/json");
		ocrStub.enqueueResponse("UNKNOWN", 200, "application/json");
		ocrStub.enqueueResponse("UNKNOWN", 200, "application/json");
		ocrStub.enqueueResponse("John Davidson", 200, "application/json");
		ocrStub.enqueueResponse("26-Jul-2026", 200, "application/json");

		this.workflow.runFieldExtraction(s.recordId(), s.jobId());

		assertEquals(5, ocrStub.getRequestCount());
		// Both candidates contributed durable outcomes and are COMPLETED.
		assertEquals("COMPLETED", docStatus(s.documentIds().get(0)));
		assertEquals("COMPLETED", docStatus(s.documentIds().get(1)));
		// Fields applied from the candidate that resolved them.
		assertEquals("Charlie Henry", this.jdbc.queryForObject(
				"SELECT policyholder_name FROM pos_record WHERE id = ?", String.class, s.recordId().toString()));
		assertEquals("John Davidson", this.jdbc.queryForObject(
				"SELECT consultant_name FROM pos_record WHERE id = ?", String.class, s.recordId().toString()));
		assertEquals("2026-07-26", this.jdbc.queryForObject(
				"SELECT policy_create_date FROM pos_record WHERE id = ?", String.class, s.recordId().toString()));
		assertEquals("COMPLETED", this.jdbc.queryForObject("SELECT status FROM ingestion_job WHERE id = ?",
				String.class, s.jobId().toString()));
	}

	@Test
	void noCallsForAlreadyResolvedFields() throws Exception {
		// All three business fields are already populated -> no OCR calls, no render.
		Setup s = createRecord(List.of("documents/LAPPe.pdf", "documents/other.pdf"),
				"Charlie Henry", "John Davidson", "2026-01-01");

		int renderBefore = renderCount();
		this.workflow.runFieldExtraction(s.recordId(), s.jobId());

		assertEquals(0, ocrStub.getRequestCount(), "all fields resolved -> no OCR requests");
		assertEquals(0, renderCount() - renderBefore, "no render when all fields are resolved");
		// The candidate has no durable outcomes -> SKIPPED; the other doc is SKIPPED too.
		assertEquals("SKIPPED", docStatus(s.documentIds().get(0)));
		assertEquals("SKIPPED", docStatus(s.documentIds().get(1)));
		// Fields unchanged; job completes.
		assertEquals("Charlie Henry", this.jdbc.queryForObject(
				"SELECT policyholder_name FROM pos_record WHERE id = ?", String.class, s.recordId().toString()));
		assertEquals("COMPLETED", this.jdbc.queryForObject("SELECT status FROM ingestion_job WHERE id = ?",
				String.class, s.jobId().toString()));
	}

	@Test
	void corruptCandidateThenUsableCandidateContinues() throws Exception {
		Setup s = createRecord(List.of("documents/LAPPe.pdf", "documents/LAPPe.pdf"), null, null, null);
		// Simulate a permanent render failure for the first candidate.
		((TestConfig.CountingRenderService) this.renderService).failDocument(s.documentIds().get(0),
				RenderingException.Code.PDF_INVALID);
		ocrStub.enqueueResponse("Charlie Henry", 200, "application/json");
		ocrStub.enqueueResponse("John Davidson", 200, "application/json");
		ocrStub.enqueueResponse("26-Jul-2026", 200, "application/json");

		this.workflow.runFieldExtraction(s.recordId(), s.jobId());

		// First candidate FAILED (permanent render failure); the workflow continued to the second.
		assertEquals("FAILED", docStatus(s.documentIds().get(0)));
		assertEquals("COMPLETED", docStatus(s.documentIds().get(1)));
		// Fields resolved from the second candidate.
		assertEquals("Charlie Henry", this.jdbc.queryForObject(
				"SELECT policyholder_name FROM pos_record WHERE id = ?", String.class, s.recordId().toString()));
		assertEquals("John Davidson", this.jdbc.queryForObject(
				"SELECT consultant_name FROM pos_record WHERE id = ?", String.class, s.recordId().toString()));
		assertEquals("2026-07-26", this.jdbc.queryForObject(
				"SELECT policy_create_date FROM pos_record WHERE id = ?", String.class, s.recordId().toString()));
		// Only the second candidate was rendered/processed: exactly 3 OCR requests.
		assertEquals(3, ocrStub.getRequestCount());
		// The job COMPLETES (it is not DLQ'd for a permanent candidate render failure).
		assertEquals("COMPLETED", this.jdbc.queryForObject("SELECT status FROM ingestion_job WHERE id = ?",
				String.class, s.jobId().toString()));
	}

	@Test
	void upsertRaceReconcilesToDurableRow() throws Exception {
		Setup s = createRecord(List.of("documents/LAPPe.pdf"), null, "John Davidson", "2026-01-01");
		// Pre-insert a durable RESOLVED outcome for the policyholder (as if a concurrent worker
		// won the upsert). The record's policyholder is still null (unresolved).
		UUID docId = s.documentIds().get(0);
		this.jdbc.update(
				"INSERT INTO pos_field_extraction (document_id, field_name, prompt_version, outcome, value_text, "
					+ "model, finish_reason, attempt_count, completed_at_epoch_ms) VALUES (?,?,?,?,?,?,?,?,?)",
				docId.toString(), "POLICYHOLDER_NAME", 2, "RESOLVED", "Durable Winner Value",
				"/models/dotsmocr-1.8b-q8_0.gguf", "stop", 1, System.currentTimeMillis());
		// The stub proposes a DIFFERENT value for the policyholder.
		ocrStub.enqueueResponse("Contender Value", 200, "application/json");

		this.workflow.runFieldExtraction(s.recordId(), s.jobId());

		// The workflow must apply the DURABLE row's value, not the locally proposed value.
		assertEquals("Durable Winner Value", this.jdbc.queryForObject(
				"SELECT policyholder_name FROM pos_record WHERE id = ?", String.class, s.recordId().toString()));
		// The outcome row still holds the durable winner's value (the upsert did not clobber it).
		assertEquals("Durable Winner Value", this.jdbc.queryForObject(
				"SELECT value_text FROM pos_field_extraction WHERE document_id = ? AND field_name = 'POLICYHOLDER_NAME'",
				String.class, docId.toString()));
	}

	// ---- new retryable response codes (malformed / empty / truncated) ----

	@Test
	void malformedResponseIsRetryableThenSucceeds() throws Exception {
		Setup s = createRecord(List.of("documents/LAPPe.pdf"), null, "John Davidson", "2026-01-01");
		ocrStub.enqueueRawResponse("not valid json {{{", 200, "application/json");
		ocrStub.enqueueRawResponse("not valid json {{{", 200, "application/json");
		ocrStub.enqueueResponse("Charlie Henry", 200, "application/json");

		this.workflow.runFieldExtraction(s.recordId(), s.jobId());

		assertEquals(3, ocrStub.getRequestCount(), "malformed responses are retried");
		assertEquals("Charlie Henry", this.jdbc.queryForObject(
				"SELECT policyholder_name FROM pos_record WHERE id = ?", String.class, s.recordId().toString()));
	}

	@Test
	void malformedResponseExhaustsRetriesAndFails() throws Exception {
		Setup s = createRecord(List.of("documents/LAPPe.pdf"), null, "John Davidson", "2026-01-01");
		ocrStub.enqueueRawResponse("not valid json {{{", 200, "application/json");
		ocrStub.enqueueRawResponse("not valid json {{{", 200, "application/json");
		ocrStub.enqueueRawResponse("not valid json {{{", 200, "application/json");

		this.workflow.runFieldExtraction(s.recordId(), s.jobId());

		assertEquals(3, ocrStub.getRequestCount());
		assertEquals("FAILED", this.jdbc.queryForObject(
				"SELECT outcome FROM pos_field_extraction WHERE document_id = ? AND field_name = 'POLICYHOLDER_NAME'",
				String.class, s.documentIds().get(0).toString()));
	}

	@Test
	void emptyOutputIsRetryable() throws Exception {
		Setup s = createRecord(List.of("documents/LAPPe.pdf"), null, "John Davidson", "2026-01-01");
		String emptyJson = "{\"model\":\"/models/dotsmocr-1.8b-q8_0.gguf\",\"choices\":[{\"message\":{\"role\":"
				+ "\"assistant\",\"content\":\"\"},\"finish_reason\":\"stop\"}]}";
		ocrStub.enqueueRawResponse(emptyJson, 200, "application/json");
		ocrStub.enqueueResponse("Charlie Henry", 200, "application/json");

		this.workflow.runFieldExtraction(s.recordId(), s.jobId());

		assertEquals(2, ocrStub.getRequestCount(), "empty output is retried");
		assertEquals("Charlie Henry", this.jdbc.queryForObject(
				"SELECT policyholder_name FROM pos_record WHERE id = ?", String.class, s.recordId().toString()));
	}

	@Test
	void truncatedOutputIsRetryable() throws Exception {
		Setup s = createRecord(List.of("documents/LAPPe.pdf"), null, "John Davidson", "2026-01-01");
		String truncatedJson = "{\"model\":\"/models/dotsmocr-1.8b-q8_0.gguf\",\"choices\":[{\"message\":{\"role\":"
				+ "\"assistant\",\"content\":\"26-Jul-\"},\"finish_reason\":\"length\"}]}";
		ocrStub.enqueueRawResponse(truncatedJson, 200, "application/json");
		ocrStub.enqueueResponse("Charlie Henry", 200, "application/json");

		this.workflow.runFieldExtraction(s.recordId(), s.jobId());

		assertEquals(2, ocrStub.getRequestCount(), "truncated output is retried");
		assertEquals("Charlie Henry", this.jdbc.queryForObject(
				"SELECT policyholder_name FROM pos_record WHERE id = ?", String.class, s.recordId().toString()));
	}

	// ---- interruption escaping ----

	@Test
	void interruptionEscapesRatherThanPersistingFailed() throws Exception {
		Setup s = createRecord(List.of("documents/LAPPe.pdf"), null, null, null);
		// The OCR client reports an interruption; the workflow must let it escape (RabbitMQ retry)
		// rather than persist a FAILED outcome.
		stubOcrClient.nextException = new OcrException(OcrException.Code.OCR_INTERRUPTED);

		ConsumerException ex = assertThrows(ConsumerException.class,
				() -> this.workflow.runFieldExtraction(s.recordId(), s.jobId()));
		Thread.interrupted(); // clear the interrupt flag set by the simulation

		assertTrue(ex.getCode().retryable(), "an interruption must be retryable (escape to consumer retry)");
		// No FAILED outcome was persisted.
		assertEquals(0, this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM pos_field_extraction WHERE document_id = ? AND outcome = 'FAILED'",
				Integer.class, s.documentIds().get(0).toString()));
		// The candidate is left in-flight (not marked FAILED or COMPLETED); the job is not completed.
		assertEquals("PROCESSING", docStatus(s.documentIds().get(0)));
		assertEquals("RUNNING", this.jdbc.queryForObject("SELECT status FROM ingestion_job WHERE id = ?",
				String.class, s.jobId().toString()));
	}

	// ---- idempotent redelivery ----

	@Test
	void idempotentRedeliveryMakesZeroNewRequests() throws Exception {
		Setup s = createRecord(List.of("documents/LAPPe.pdf", "documents/other.pdf"), null, null, null);
		ocrStub.enqueueResponse("Charlie Henry", 200, "application/json");
		ocrStub.enqueueResponse("John Davidson", 200, "application/json");
		ocrStub.enqueueResponse("26-Jul-2026", 200, "application/json");

		this.workflow.runFieldExtraction(s.recordId(), s.jobId());
		assertEquals(3, ocrStub.getRequestCount());

		// Simulate a redelivery where the durable outcomes and resolved business
		// fields are committed but the job/record completion was not (crash before
		// completion): rewind the job and record to their in-flight states. (The
		// consumer separately no-ops an already-COMPLETED job; this exercises the
		// workflow-level idempotency on crash recovery.)
		this.jdbc.update("UPDATE ingestion_job SET status = 'RUNNING', completed_at_epoch_ms = NULL "
				+ "WHERE id = ?", s.jobId().toString());
		this.jdbc.update("UPDATE pos_record SET status = 'PROCESSING' WHERE id = ?", s.recordId().toString());

		// Second delivery: every field is resolved or has a durable outcome -> 0 new requests.
		this.workflow.runFieldExtraction(s.recordId(), s.jobId());
		assertEquals(3, ocrStub.getRequestCount(), "redelivery must make zero new OCR requests");
		assertEquals(3, this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM pos_field_extraction WHERE document_id = ?", Integer.class,
				s.documentIds().get(0).toString()),
				"redelivery must create no duplicate outcome rows");
		// The non-candidate is never treated as the candidate.
		assertEquals("SKIPPED", docStatus(s.documentIds().get(1)));
		assertEquals("COMPLETED", docStatus(s.documentIds().get(0)));
	}

	// ---- helpers ----

	private int renderCount() {
		return ((TestConfig.CountingRenderService) this.renderService).getPrepareCount();
	}

	private String docStatus(UUID docId) {
		return this.jdbc.queryForObject("SELECT processing_status FROM pos_document WHERE id = ?", String.class,
				docId.toString());
	}

	private String promptOf(OcrHttpStub.RecordedRequest request) throws Exception {
		JsonNode root = this.objectMapper.readTree(request.body());
		return root.get("messages").get(0).get("content").get(1).get("text").asText();
	}

	private String pngOf(OcrHttpStub.RecordedRequest request) throws Exception {
		JsonNode root = this.objectMapper.readTree(request.body());
		String url = root.get("messages").get(0).get("content").get(0).get("image_url").get("url").asText();
		String base64 = url.substring("data:image/png;base64,".length());
		byte[] decoded = Base64.getDecoder().decode(base64);
		// Assert it is a real PNG (magic bytes) and stable via its content hash.
		assertTrue(decoded.length > 8 && decoded[0] == (byte) 0x89 && decoded[1] == 'P',
				"request must carry a PNG payload");
		return sha256Hex(decoded);
	}

	private static String sha256Hex(byte[] bytes) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		digest.update(bytes);
		StringBuilder sb = new StringBuilder();
		for (byte b : digest.digest()) {
			sb.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
		}
		return sb.toString();
	}

	/**
	 * Overrides the render service with a counting wrapper (same real PDFBox
	 * pipeline) so tests can assert the candidate is rendered exactly once, and
	 * installs a zero-time backoff so retry tests run fast and deterministically.
	 */
	@TestConfiguration
	static class TestConfig {

		@Bean
		@Primary
		FirstPageRenderPreparationService countingRenderService(DocumentRenderSourceService sourceService,
				StoredPdfMaterializer materializer, PdfFirstPageRenderer renderer) {
			return new CountingRenderService(sourceService, materializer, renderer);
		}

		@Bean
		@Primary
		ExtractionBackoff noOpBackoff() {
			return ms -> {
				// zero-time backoff for deterministic, fast tests
			};
		}

		@Bean
		@Primary
		LlamaCppOcrClient stubOcrClient(OkHttpClient ocrOkHttpClient, LlamaCppOcrProperties properties) {
			StubOcrClient client = new StubOcrClient(ocrOkHttpClient, properties);
			StructuredFieldExtractionServiceIntegrationTest.stubOcrClient = client;
			return client;
		}

		/**
		 * Delegates to the real PDFBox pipeline, counts {@code prepare} calls, and can be
		 * configured to fail permanently for a specific document (simulating a corrupt PDF).
		 */
		static final class CountingRenderService extends FirstPageRenderPreparationService {

			private int prepareCount;
			private UUID failDocument;
			private RenderingException.Code failCode;

			CountingRenderService(DocumentRenderSourceService sourceService, StoredPdfMaterializer materializer,
					PdfFirstPageRenderer renderer) {
				super(sourceService, materializer, renderer);
			}

			void failDocument(UUID documentId, RenderingException.Code code) {
				this.failDocument = documentId;
				this.failCode = code;
			}

			@Override
			public RenderedFirstPage prepare(UUID documentId) {
				this.prepareCount++;
				if (this.failDocument != null && this.failDocument.equals(documentId)) {
					throw new RenderingException(this.failCode);
				}
				return super.prepare(documentId);
			}

			int getPrepareCount() {
				return this.prepareCount;
			}

		}

	}

	/**
	 * OCR client test double that can be configured to throw a specific
	 * {@link OcrException} on the next call (used to test interruption escaping).
	 * Otherwise delegates to the real client logic.
	 */
	static final class StubOcrClient extends LlamaCppOcrClient {

		volatile OcrException nextException;

		StubOcrClient(OkHttpClient client, LlamaCppOcrProperties properties) {
			super(client, properties);
		}

		@Override
		public OcrResult recognize(RenderedFirstPage page, String prompt, int promptVersion) {
			OcrException ex = this.nextException;
			if (ex != null) {
				this.nextException = null;
				if (ex.getCode() == OcrException.Code.OCR_INTERRUPTED) {
					Thread.currentThread().interrupt();
				}
				throw ex;
			}
			return super.recognize(page, prompt, promptVersion);
		}

	}

}
