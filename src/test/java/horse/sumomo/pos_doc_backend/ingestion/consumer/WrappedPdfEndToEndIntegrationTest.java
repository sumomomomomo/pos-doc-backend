package horse.sumomo.pos_doc_backend.ingestion.consumer;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

import horse.sumomo.pos_doc_backend.ingestion.messaging.OutboxRelay;
import horse.sumomo.pos_doc_backend.ingestion.testsupport.SyntheticPdfFactory;
import horse.sumomo.pos_doc_backend.infrastructure.minio.MinioObjectStorage;
import horse.sumomo.pos_doc_backend.ocr.testsupport.OcrHttpStub;
import horse.sumomo.pos_doc_backend.rendering.service.PdfFirstPageRenderer;
import horse.sumomo.pos_doc_backend.rendering.service.StoredPdfMaterializer;
import horse.sumomo.pos_doc_backend.rendering.service.TempFileFactory;
import horse.sumomo.pos_doc_backend.security.OidcTestAuth;

import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end integration test for Java-serialized {@code byte[]} (wrapped) PDF
 * entries that goes through the <em>public upload/intake path</em>: real
 * temporary SQLite, real test MinIO, real RabbitMQ, the real controller +
 * {@code PosArchiveIntakeService} + outbox relay + consumer, a real PDFBox
 * renderer, and an ephemeral fake llama.cpp server.
 *
 * <p>Each test submits a ZIP with three PDF entries (one raw, two wrapped via
 * {@code ObjectOutputStream}) through the {@code /pos-records} upload endpoint
 * — the exact intake workflow that originally rejected the wrapped form — then
 * verifies preservation and processing, covering both candidate variants:
 * <ul>
 *   <li>first test: the {@code LAPPe.pdf} candidate is <em>wrapped</em>,</li>
 *   <li>second test: the {@code LAPPe.pdf} candidate is <em>raw</em>.</li>
 * </ul>
 * For each:
 * <ul>
 *   <li>the upload endpoint accepts the ZIP (202) — proving the intake
 *       validation now accepts the wrapped form,</li>
 *   <li>the archive object in MinIO is byte-for-byte identical to the upload
 *       and still contains the wrappers,</li>
 *   <li>the job completes, the record reaches REVIEW_REQUIRED,</li>
 *   <li>the candidate is COMPLETED and the non-candidates are SKIPPED,</li>
 *   <li>each individual MinIO object holds only the normalized PDF (byte zero
 *       is {@code %PDF-}, never the {@code AC ED 00 05} envelope),</li>
 *   <li>the normalized candidate PDF still loads with PDFBox.</li>
 * </ul>
 *
 * <p>All PDF content and names are synthetic; no real PII is used.
 */
@SpringBootTest(properties = {
		"app.messaging.outbox.enabled=true",
		"app.messaging.outbox.fixed-delay-ms=3600000",
		"app.ingestion.consumer.enabled=true"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WrappedPdfEndToEndIntegrationTest {

	private static final String TEST_BUCKET = "pos-documents-wrapped-e2e-test";
	private static final DockerImageName MINIO_IMAGE =
			DockerImageName.parse("cgr.dev/chainguard/minio:latest").asCompatibleSubstituteFor("minio/minio");

	private static final byte[] PDF_CANDIDATE = SyntheticPdfFactory.createPdf("LAPPe Candidate Doc");
	private static final byte[] PDF_SECOND = SyntheticPdfFactory.createPdf("Second Wrapped Doc");
	private static final byte[] PDF_THIRD = SyntheticPdfFactory.createPdf("Third Doc");

	private static final byte[] PDF_MAGIC = { '%', 'P', 'D', 'F', '-' };
	private static final byte[] SERIALIZED_PREFIX = { (byte) 0xAC, (byte) 0xED, 0x00, 0x05 };

	private static MinIOContainer minio;
	private static RabbitMQContainer rabbit;
	private static MinioClient adminClient;
	private static OcrHttpStub ocrStub;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private MinioObjectStorage storage;

	@Autowired
	private OutboxRelay relay;

	@Autowired
	private JdbcTemplate jdbc;

	@DynamicPropertySource
	static void containerProperties(DynamicPropertyRegistry registry) throws Exception {
		minio = new MinIOContainer(MINIO_IMAGE)
				.withUserName("wrapped-e2e-access-key")
				.withPassword("wrapped-e2e-secret-change-me");
		minio.start();
		adminClient = MinioClient.builder()
				.endpoint(minio.getS3URL())
				.credentials(minio.getUserName(), minio.getPassword())
				.build();
		adminClient.makeBucket(MakeBucketArgs.builder().bucket(TEST_BUCKET).build());

		rabbit = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.3.5-management"));
		rabbit.start();

		ocrStub = new OcrHttpStub("SYNTHETIC OCR TEXT", 200, "application/json");

		registry.add("storage.minio.endpoint", minio::getS3URL);
		registry.add("storage.minio.access-key", minio::getUserName);
		registry.add("storage.minio.secret-key", minio::getPassword);
		registry.add("storage.minio.bucket", () -> TEST_BUCKET);

		registry.add("spring.rabbitmq.host", rabbit::getHost);
		registry.add("spring.rabbitmq.port", rabbit::getAmqpPort);
		registry.add("spring.rabbitmq.username", rabbit::getAdminUsername);
		registry.add("spring.rabbitmq.password", rabbit::getAdminPassword);

		registry.add("app.ocr.llama-cpp.server-origin", ocrStub::getServerOrigin);
		registry.add("app.ocr.llama-cpp.model", () -> OcrHttpStub.MODEL);

		Path sqliteDbFile = Files.createTempFile("pos-doc-wrapped-e2e-test", ".db");
		sqliteDbFile.toFile().deleteOnExit();
		registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + sqliteDbFile.toAbsolutePath());
	}

	@AfterAll
	static void stopContainers() throws Exception {
		if (rabbit != null && rabbit.isRunning()) {
			rabbit.stop();
		}
		if (minio != null && minio.isRunning()) {
			minio.stop();
		}
		if (adminClient != null) {
			adminClient.close();
		}
		if (ocrStub != null) {
			ocrStub.close();
		}
	}

	@Test
	void wrappedLapPeCandidateIsAcceptedThroughUploadAndNormalized() throws Exception {
		// Candidate (LAPPe.pdf) is WRAPPED; the other two: one wrapped, one raw.
		runAndAssert(true);
	}

	@Test
	void rawLapPeCandidateIsAcceptedThroughUploadAndNormalized() throws Exception {
		// Candidate (LAPPe.pdf) is RAW; the other two are both wrapped.
		runAndAssert(false);
	}

	private void runAndAssert(boolean candidateWrapped) throws Exception {
		ocrStub.resetResponses();

		// Three PDF entries: one raw, two wrapped. The candidate is wrapped in
		// the first variant and raw in the second.
		Map<String, byte[]> entries = new LinkedHashMap<>();
		entries.put("LAPPe.pdf", candidateWrapped ? serialize(PDF_CANDIDATE) : PDF_CANDIDATE);
		entries.put("second.pdf", serialize(PDF_SECOND));
		entries.put("third.pdf", candidateWrapped ? PDF_THIRD : serialize(PDF_THIRD));
		byte[] zipBytes = zipBytes(entries);

		// 1. Submit the ZIP through the public upload endpoint. The intake
		//    path validates the archive via ZipArchiveValidator — the path that
		//    originally rejected the wrapped form.
		String filename = candidateWrapped ? "EREF-WRAPPED-CAND.zip" : "EREF-RAW-CAND.zip";
		MockMultipartFile file = new MockMultipartFile("file", filename, "application/zip", zipBytes);
		MvcResult result = this.perform(multipart("/pos-records").file(file))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("UPLOADED"))
				.andExpect(jsonPath("$.posRecordId").isNotEmpty())
				.andExpect(jsonPath("$.jobId").isNotEmpty())
				.andReturn();
		String posRecordId = readJsonField(result, "posRecordId");
		String jobId = readJsonField(result, "jobId");

		// 2. Preservation: the archive object in MinIO is byte-for-byte the
		//    upload and still contains the wrappers.
		String storageObjectId = this.jdbc.queryForObject(
				"SELECT source_archive_id FROM pos_record WHERE id = ?", String.class, posRecordId);
		String archiveKey = this.jdbc.queryForObject(
				"SELECT object_key FROM storage_object WHERE id = ?", String.class, storageObjectId);
		byte[] storedArchive = readMinioBytes(archiveKey);
		assertArrayEquals(zipBytes, storedArchive, "the stored archive must be byte-for-byte identical");
		assertTrue(entryStartsWith(storedArchive, "second.pdf", SERIALIZED_PREFIX),
				"the stored archive's second.pdf must still carry the wrapper");
		if (candidateWrapped) {
			assertTrue(entryStartsWith(storedArchive, "LAPPe.pdf", SERIALIZED_PREFIX),
					"the stored archive's wrapped candidate must still carry the wrapper");
			assertTrue(entryStartsWith(storedArchive, "third.pdf", PDF_MAGIC),
					"the stored archive's raw entry must remain raw");
		}
		else {
			assertTrue(entryStartsWith(storedArchive, "LAPPe.pdf", PDF_MAGIC),
					"the stored archive's raw candidate must remain raw");
			assertTrue(entryStartsWith(storedArchive, "third.pdf", SERIALIZED_PREFIX),
					"the stored archive's wrapped entry must still carry the wrapper");
		}

		// 3. Queue the three field responses so the candidate resolves on its
		//    first attempt (workflow order: policyholder, consultant, date),
		//    then publish the outbox event so the consumer processes the job.
		ocrStub.enqueueResponse("Charlie Henry", 200, "application/json");
		ocrStub.enqueueResponse("John Davidson", 200, "application/json");
		ocrStub.enqueueResponse("26-Jul-2026", 200, "application/json");
		this.relay.relayOnce();

		// 4. Wait for the job to reach a terminal status.
		AtomicReference<String> finalJobStatus = new AtomicReference<>();
		await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(250)).until(() -> {
			String s = this.jdbc.queryForObject("SELECT status FROM ingestion_job WHERE id = ?", String.class, jobId);
			finalJobStatus.set(s);
			return "COMPLETED".equals(s) || "FAILED".equals(s);
		});
		assertEquals("COMPLETED", finalJobStatus.get(), "Job must reach COMPLETED");

		// 5. Processing: record REVIEW_REQUIRED, three documents, candidate
		//    COMPLETED, non-candidates SKIPPED, normalized objects, and a
		//    PDFBox-loadable candidate.
		assertEquals("REVIEW_REQUIRED", this.jdbc.queryForObject("SELECT status FROM pos_record WHERE id = ?",
				String.class, posRecordId));

		int docCount = this.jdbc.queryForObject("SELECT count(*) FROM pos_document WHERE pos_record_id = ?",
				Integer.class, posRecordId);
		assertEquals(3, docCount, "Three pos_document rows must exist");

		assertEquals("COMPLETED", statusFor(posRecordId, "LAPPe.pdf"), "the LAPPe.pdf candidate must be COMPLETED");
		assertEquals("SKIPPED", statusFor(posRecordId, "second.pdf"), "second.pdf must be SKIPPED");
		assertEquals("SKIPPED", statusFor(posRecordId, "third.pdf"), "third.pdf must be SKIPPED");

		byte[] storedCandidate = readDocument(posRecordId, "LAPPe.pdf");
		byte[] storedSecond = readDocument(posRecordId, "second.pdf");
		byte[] storedThird = readDocument(posRecordId, "third.pdf");
		assertArrayEquals(PDF_CANDIDATE, storedCandidate, "candidate must be stored normalized");
		assertArrayEquals(PDF_SECOND, storedSecond, "second must be stored normalized");
		assertArrayEquals(PDF_THIRD, storedThird, "third must be stored normalized");
		assertTrue(startsWith(storedCandidate, PDF_MAGIC), "candidate must begin with %PDF-");
		assertTrue(startsWith(storedSecond, PDF_MAGIC), "second must begin with %PDF-");
		assertTrue(startsWith(storedThird, PDF_MAGIC), "third must begin with %PDF-");
		assertFalse(startsWith(storedCandidate, SERIALIZED_PREFIX), "candidate must not carry the Java envelope");
		assertFalse(startsWith(storedSecond, SERIALIZED_PREFIX), "second must not carry the Java envelope");
		assertFalse(startsWith(storedThird, SERIALIZED_PREFIX), "third must not carry the Java envelope");

		try (PDDocument doc = Loader.loadPDF(storedCandidate)) {
			assertTrue(doc.getNumberOfPages() >= 1, "the normalized candidate must render/load");
		}
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	private ResultActions perform(AbstractMockHttpServletRequestBuilder builder) throws Exception {
		return this.mockMvc.perform(builder
				.with(OidcTestAuth.oidc("pos-doc-test-subject-reviewer", true))
				.with(csrf()));
	}

	private String statusFor(String posRecordId, String filename) {
		return this.jdbc.queryForObject(
				"SELECT d.processing_status FROM pos_document d "
						+ "JOIN storage_object s ON d.storage_object_id = s.id "
						+ "WHERE d.pos_record_id = ? AND s.original_filename = ?",
				String.class, posRecordId, filename);
	}

	private byte[] readDocument(String posRecordId, String filename) throws Exception {
		String objectKey = this.jdbc.queryForObject(
				"SELECT s.object_key FROM pos_document d "
						+ "JOIN storage_object s ON d.storage_object_id = s.id "
						+ "WHERE d.pos_record_id = ? AND s.original_filename = ?",
				String.class, posRecordId, filename);
		return readMinioBytes(objectKey);
	}

	private byte[] readMinioBytes(String objectKey) throws Exception {
		try (InputStream stream = this.storage.get(objectKey);
				ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			stream.transferTo(out);
			return out.toByteArray();
		}
	}

	private static String readJsonField(MvcResult result, String field) throws Exception {
		String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
		int idx = body.indexOf("\"" + field + "\":\"");
		if (idx < 0) {
			throw new IllegalStateException("field not found in response: " + field);
		}
		int start = idx + field.length() + 4;
		int end = body.indexOf('"', start);
		return body.substring(start, end);
	}

	private static byte[] serialize(byte[] pdfBytes) {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		try (java.io.ObjectOutputStream oos = new java.io.ObjectOutputStream(bos)) {
			oos.writeObject(pdfBytes);
		}
		catch (java.io.IOException e) {
			throw new UncheckedIOException(e);
		}
		return bos.toByteArray();
	}

	private static boolean startsWith(byte[] bytes, byte[] prefix) {
		if (bytes.length < prefix.length) {
			return false;
		}
		for (int i = 0; i < prefix.length; i++) {
			if (bytes[i] != prefix[i]) {
				return false;
			}
		}
		return true;
	}

	private static boolean entryStartsWith(byte[] zipBytes, String name, byte[] prefix) throws Exception {
		try (var zin = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zipBytes))) {
			ZipEntry entry;
			while ((entry = zin.getNextEntry()) != null) {
				if (entry.getName().equals(name)) {
					byte[] head = new byte[prefix.length];
					int read = 0;
					while (read < prefix.length) {
						int r = zin.read(head, read, prefix.length - read);
						if (r == -1) {
							return false;
						}
						read += r;
					}
					return startsWith(head, prefix);
				}
			}
		}
		return false;
	}

	private static byte[] zipBytes(Map<String, byte[]> entries) throws Exception {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(baos)) {
			for (Map.Entry<String, byte[]> e : entries.entrySet()) {
				ZipEntry entry = new ZipEntry(e.getKey());
				zip.putNextEntry(entry);
				zip.write(e.getValue());
				zip.closeEntry();
			}
		}
		return baos.toByteArray();
	}

	@TestConfiguration
	static class TestRenderConfig {

		private Path testTempDir;

		@Bean
		@Primary
		TempFileFactory testTempFileFactory() throws java.io.IOException {
			this.testTempDir = Files.createTempDirectory("pos-doc-wrapped-e2e-render-");
			return TempFileFactory.inDirectory(this.testTempDir);
		}

		@Bean
		@Primary
		PdfFirstPageRenderer pdfFirstPageRenderer(
				horse.sumomo.pos_doc_backend.rendering.api.FirstPageRenderingProperties properties,
				TempFileFactory testTempFileFactory) {
			return new PdfFirstPageRenderer(properties, testTempFileFactory);
		}

		@Bean
		@Primary
		StoredPdfMaterializer storedPdfMaterializer(
				MinioObjectStorage storage,
				horse.sumomo.pos_doc_backend.rendering.api.FirstPageRenderingProperties properties,
				TempFileFactory testTempFileFactory) {
			return new StoredPdfMaterializer(storage, properties, testTempFileFactory);
		}

		@jakarta.annotation.PreDestroy
		void cleanup() {
			if (this.testTempDir != null) {
				try (var stream = Files.list(this.testTempDir)) {
					stream.forEach(p -> {
						try {
							Files.deleteIfExists(p);
						}
						catch (java.io.IOException ignored) {
						}
					});
				}
				catch (java.io.IOException ignored) {
				}
				try {
					Files.deleteIfExists(this.testTempDir);
				}
				catch (java.io.IOException ignored) {
				}
			}
		}

	}

}
