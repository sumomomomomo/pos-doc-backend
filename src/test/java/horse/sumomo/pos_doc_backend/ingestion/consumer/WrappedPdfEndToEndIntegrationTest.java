package horse.sumomo.pos_doc_backend.ingestion.consumer;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

import horse.sumomo.pos_doc_backend.ingestion.api.RabbitTopologyProperties;
import horse.sumomo.pos_doc_backend.ingestion.messaging.IngestionRequestedMessage;
import horse.sumomo.pos_doc_backend.ingestion.testsupport.SyntheticPdfFactory;
import horse.sumomo.pos_doc_backend.infrastructure.minio.MinioObjectStorage;
import horse.sumomo.pos_doc_backend.ocr.testsupport.OcrHttpStub;
import horse.sumomo.pos_doc_backend.rendering.service.PdfFirstPageRenderer;
import horse.sumomo.pos_doc_backend.rendering.service.StoredPdfMaterializer;
import horse.sumomo.pos_doc_backend.rendering.service.TempFileFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end integration test for Java-serialized {@code byte[]} (wrapped) PDF
 * entries: real temporary SQLite, real test MinIO, real RabbitMQ, real PDFBox
 * renderer, and an ephemeral fake llama.cpp server.
 *
 * <p>Proves the wrapped form end to end, covering both candidate variants
 * across two tests:
 * <ul>
 *   <li>a ZIP with three PDF entries — one raw, two wrapped via
 *       {@code ObjectOutputStream} — with a {@code LAPPe.pdf} candidate (first
 *       test: the candidate is <em>wrapped</em>; second test: the candidate is
 *       <em>raw</em>),</li>
 *   <li>the job completes and the record reaches REVIEW_REQUIRED,</li>
 *   <li>the candidate is COMPLETED and the non-candidates are SKIPPED,</li>
 *   <li>each individual MinIO object holds only the normalized PDF (byte zero
 *       is {@code %PDF-}, never the {@code AC ED 00 05} envelope),</li>
 *   <li>the normalized candidate PDF still loads with PDFBox,</li>
 *   <li>the original ZIP object in MinIO is byte-for-byte identical to the
 *       upload and still contains the wrappers.</li>
 * </ul>
 *
 * <p>All PDF content and names are synthetic; no real PII is used.
 */
@SpringBootTest(properties = {
		"app.messaging.outbox.enabled=false",
		"app.ingestion.consumer.enabled=true"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WrappedPdfEndToEndIntegrationTest {

	private static final String TEST_BUCKET = "pos-documents-wrapped-e2e-test";
	private static final DockerImageName MINIO_IMAGE =
			DockerImageName.parse("cgr.dev/chainguard/minio:latest").asCompatibleSubstituteFor("minio/minio");

	private static final byte[] PDF_CANDIDATE = SyntheticPdfFactory.createPdf("LAPPe Candidate Doc");
	private static final byte[] PDF_SECOND = SyntheticPdfFactory.createPdf("Second Wrapped Doc");
	private static final byte[] PDF_THIRD = SyntheticPdfFactory.createPdf("Third Doc");
	private static final String MODEL = "task12-test-model";

	private static final byte[] PDF_MAGIC = { '%', 'P', 'D', 'F', '-' };
	private static final byte[] SERIALIZED_PREFIX = { (byte) 0xAC, (byte) 0xED, 0x00, 0x05 };

	private static MinIOContainer minio;
	private static RabbitMQContainer rabbit;
	private static MinioClient adminClient;
	private static OcrHttpStub ocrStub;

	@Autowired
	private RabbitTemplate rabbitTemplate;

	@Autowired
	private MinioObjectStorage storage;

	@Autowired
	private RabbitTopologyProperties topology;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private JsonMapper json;

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
		registry.add("app.ocr.llama-cpp.model", () -> MODEL);

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
	void wrappedLapPeCandidateIsNormalizedAndArchivePreserved() throws Exception {
		// Candidate (LAPPe.pdf) is WRAPPED; the other two: one wrapped, one raw.
		runAndAssert(true);
	}

	@Test
	void rawLapPeCandidateIsNormalizedAndArchivePreserved() throws Exception {
		// Candidate (LAPPe.pdf) is RAW; the other two are both wrapped.
		runAndAssert(false);
	}

	private void runAndAssert(boolean candidateWrapped) throws Exception {
		UUID posRecordId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID eventId = UUID.randomUUID();
		Instant occurredAt = Instant.parse("2026-01-02T03:04:05Z");

		// Three PDF entries: one raw, two wrapped. The candidate is wrapped in
		// the first variant and raw in the second.
		Map<String, byte[]> entries = new LinkedHashMap<>();
		byte[] candidateEntry = candidateWrapped ? serialize(PDF_CANDIDATE) : PDF_CANDIDATE;
		byte[] secondEntry = candidateWrapped ? serialize(PDF_SECOND) : serialize(PDF_SECOND);
		byte[] thirdEntry = candidateWrapped ? PDF_THIRD : serialize(PDF_THIRD);
		entries.put("LAPPe.pdf", candidateEntry);
		entries.put("second.pdf", secondEntry);
		entries.put("third.pdf", thirdEntry);
		byte[] zipBytes = zipBytes(entries);

		// Upload the source archive to MinIO and record the intake metadata.
		String objectKey = "archives/" + posRecordId + "/" + UUID.randomUUID() + ".zip";
		try (var in = new ByteArrayInputStream(zipBytes)) {
			this.storage.put(objectKey, in, zipBytes.length, "application/zip");
		}
		UUID storageObjectId = UUID.randomUUID();
		this.jdbc.update("INSERT INTO storage_object (id, object_key, original_filename, content_type, "
				+ "byte_size, sha256, created_at_epoch_ms) VALUES (?,?,?,?,?,?,?)", storageObjectId.toString(),
				objectKey, "EREF-WRAPPED-E2E.zip", "application/zip", zipBytes.length, sha256Hex(zipBytes),
				occurredAt.toEpochMilli());
		this.jdbc.update("INSERT INTO pos_record (id, source_archive_id, status, uploaded_by, "
				+ "uploaded_at_epoch_ms, updated_at_epoch_ms, version) VALUES (?,?,?,?,?,?,?)",
				posRecordId.toString(), storageObjectId.toString(), "UPLOADED", "test-uploader",
				occurredAt.toEpochMilli(), occurredAt.toEpochMilli(), 0L);
		this.jdbc.update("INSERT INTO ingestion_job (id, pos_record_id, status, attempt_count, "
				+ "created_at_epoch_ms, version) VALUES (?,?,?,?,?,?)", jobId.toString(),
				posRecordId.toString(), "QUEUED", 0L, occurredAt.toEpochMilli(), 0L);

		// Queue the three field responses so the candidate resolves on first
		// attempt (workflow order: policyholder, consultant, date).
		ocrStub.enqueueResponse("Charlie Henry", 200, "application/json");
		ocrStub.enqueueResponse("John Davidson", 200, "application/json");
		ocrStub.enqueueResponse("26-Jul-2026", 200, "application/json");

		send(jobId, posRecordId, eventId, occurredAt);

		AtomicReference<String> finalJobStatus = new AtomicReference<>();
		await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(250)).until(() -> {
			String s = this.jdbc.queryForObject("SELECT status FROM ingestion_job WHERE id = ?", String.class,
					jobId.toString());
			finalJobStatus.set(s);
			return "COMPLETED".equals(s) || "FAILED".equals(s);
		});
		assertEquals("COMPLETED", finalJobStatus.get(), "Job must reach COMPLETED");

		// Record reaches REVIEW_REQUIRED (extraction is best-effort, review
		// still required).
		assertEquals("REVIEW_REQUIRED", this.jdbc.queryForObject("SELECT status FROM pos_record WHERE id = ?",
				String.class, posRecordId.toString()));

		// Three documents are stored.
		int docCount = this.jdbc.queryForObject("SELECT count(*) FROM pos_document WHERE pos_record_id = ?",
				Integer.class, posRecordId.toString());
		assertEquals(3, docCount, "Three pos_document rows must exist");

		// Candidate is COMPLETED; non-candidates are SKIPPED.
		assertEquals("COMPLETED", statusFor(posRecordId, "LAPPe.pdf"), "the LAPPe.pdf candidate must be COMPLETED");
		assertEquals("SKIPPED", statusFor(posRecordId, "second.pdf"), "second.pdf must be SKIPPED");
		assertEquals("SKIPPED", statusFor(posRecordId, "third.pdf"), "third.pdf must be SKIPPED");

		// Every individual MinIO object holds only the normalized PDF.
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

		// The normalized candidate PDF still loads with PDFBox.
		try (PDDocument doc = Loader.loadPDF(storedCandidate)) {
			assertTrue(doc.getNumberOfPages() >= 1, "the normalized candidate must render/load");
		}

		// The original ZIP object in MinIO is byte-for-byte the upload and
		// still contains the wrappers.
		byte[] storedArchive = readMinioBytes(objectKey);
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
	}

	private String statusFor(UUID posRecordId, String filename) {
		return this.jdbc.queryForObject(
				"SELECT d.processing_status FROM pos_document d "
						+ "JOIN storage_object s ON d.storage_object_id = s.id "
						+ "WHERE d.pos_record_id = ? AND s.original_filename = ?",
				String.class, posRecordId.toString(), filename);
	}

	private byte[] readDocument(UUID posRecordId, String filename) throws Exception {
		String objectKey = this.jdbc.queryForObject(
				"SELECT s.object_key FROM pos_document d "
						+ "JOIN storage_object s ON d.storage_object_id = s.id "
						+ "WHERE d.pos_record_id = ? AND s.original_filename = ?",
				String.class, posRecordId.toString(), filename);
		return readMinioBytes(objectKey);
	}

	private byte[] readMinioBytes(String objectKey) throws Exception {
		try (var stream = this.storage.get(objectKey);
				ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			stream.transferTo(out);
			return out.toByteArray();
		}
	}

	private void send(UUID jobId, UUID posRecordId, UUID eventId, Instant occurredAt) throws Exception {
		IngestionRequestedMessage message = new IngestionRequestedMessage(eventId, jobId, posRecordId, 1, occurredAt);
		byte[] payload = this.json.writeValueAsBytes(message);
		MessageProperties props = new MessageProperties();
		props.setContentType("application/json");
		props.setContentEncoding("UTF-8");
		props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
		props.setType("INGESTION_REQUESTED");
		props.setMessageId(eventId.toString());
		props.setCorrelationId(jobId.toString());
		this.rabbitTemplate.send(topology.exchange(), topology.routingKey(), new Message(payload, props));
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

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
		try (var zin = new java.util.zip.ZipInputStream(new ByteArrayInputStream(zipBytes))) {
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

	private static String sha256Hex(byte[] bytes) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		digest.update(bytes);
		StringBuilder sb = new StringBuilder();
		for (byte b : digest.digest()) {
			sb.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
		}
		return sb.toString();
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
