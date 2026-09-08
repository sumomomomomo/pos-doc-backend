package horse.sumomo.pos_doc_backend.ingestion.consumer;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import horse.sumomo.pos_doc_backend.rendering.service.PdfFirstPageRenderer;
import horse.sumomo.pos_doc_backend.rendering.service.StoredPdfMaterializer;
import horse.sumomo.pos_doc_backend.rendering.service.TempFileFactory;
import horse.sumomo.pos_doc_backend.ocr.testsupport.OcrHttpStub;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end integration test for Task 9: real temporary SQLite, real
 * test MinIO, real RabbitMQ test container, and an ephemeral fake
 * llama.cpp HTTP server.
 *
 * <p>Proves the complete flow with one ZIP containing two valid PDFs
 * created by {@link SyntheticPdfFactory}:
 * <ol>
 *   <li>The existing message triggers extraction.</li>
 *   <li>Two pos_document rows and their storage objects exist.</li>
 *   <li>The fake OCR server receives exactly two requests on ordinary
 *       success.</li>
 *   <li>Two version-1 OCR results are stored with the expected synthetic
 *       text and safe metadata.</li>
 *   <li>Both documents become COMPLETED.</li>
 *   <li>The ingestion job becomes COMPLETED.</li>
 *   <li>The POS record becomes REVIEW_REQUIRED, not COMPLETED.</li>
 *   <li>Redelivering the same message performs zero additional OCR
 *       requests and creates no duplicate rows. Deterministic ACK
 *       evidence: wait for the job to remain COMPLETED and the OCR
 *       request count to remain stable after the redelivery is
 *       consumed.</li>
 *   <li>No temporary rendered PNG remains in the test-specific render
 *       directory after the workflow finishes.</li>
 * </ol>
 */
@SpringBootTest(properties = {
		"app.messaging.outbox.enabled=false",
		"app.ingestion.consumer.enabled=true"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DocumentOcrEndToEndIntegrationTest {

	private static final String TEST_BUCKET = "pos-documents-ocr-e2e-test";
	private static final DockerImageName MINIO_IMAGE =
			DockerImageName.parse("minio/minio:RELEASE.2025-09-07T16-13-09Z");
	private static final byte[] PDF_A = SyntheticPdfFactory.createPdf("Doc A");
	private static final byte[] PDF_B = SyntheticPdfFactory.createPdf("Doc B");
	private static final String SYNTHETIC_OCR_TEXT = "SYNTHETIC OCR TEXT";

	private static MinIOContainer minio;
	private static RabbitMQContainer rabbit;
	private static MinioClient adminClient;
	private static OcrHttpStub ocrStub;

	@Autowired
	private TempFileFactory testTempFileFactory;

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
				.withUserName("ocr-e2e-access-key")
				.withPassword("ocr-e2e-secret-change-me");
		minio.start();
		adminClient = MinioClient.builder()
				.endpoint(minio.getS3URL())
				.credentials(minio.getUserName(), minio.getPassword())
				.build();
		adminClient.makeBucket(MakeBucketArgs.builder().bucket(TEST_BUCKET).build());

		rabbit = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.3.5-management"));
		rabbit.start();

		ocrStub = new OcrHttpStub(SYNTHETIC_OCR_TEXT, 200, "application/json");

		registry.add("storage.minio.endpoint", minio::getS3URL);
		registry.add("storage.minio.access-key", minio::getUserName);
		registry.add("storage.minio.secret-key", minio::getPassword);
		registry.add("storage.minio.bucket", () -> TEST_BUCKET);

		registry.add("spring.rabbitmq.host", rabbit::getHost);
		registry.add("spring.rabbitmq.port", rabbit::getAmqpPort);
		registry.add("spring.rabbitmq.username", rabbit::getAdminUsername);
		registry.add("spring.rabbitmq.password", rabbit::getAdminPassword);

		// Point the OCR client at the ephemeral stub.
		registry.add("app.ocr.llama-cpp.server-origin", ocrStub::getServerOrigin);

		Path sqliteDbFile = Files.createTempFile("pos-doc-ocr-e2e-test", ".db");
		sqliteDbFile.toFile().deleteOnExit();
		Path.of(sqliteDbFile.toString() + "-wal").toFile().deleteOnExit();
		Path.of(sqliteDbFile.toString() + "-shm").toFile().deleteOnExit();
		registry.add("SQLITE_URL", () -> "jdbc:sqlite:" + sqliteDbFile);
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
	void endToEndOcrWorkflowCompletesAllDocumentsAndJob() throws Exception {
		// The test-owned render temp directory is injected via
		// @TestConfiguration (see TestRenderConfig below). We assert
		// it is empty after the workflow completes.

		UUID posRecordId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID eventId = UUID.randomUUID();
		Instant occurredAt = Instant.parse("2026-01-02T03:04:05Z");

		// Upload the ZIP and set up the metadata rows.
		String objectKey = "archives/" + posRecordId + "/" + UUID.randomUUID() + ".zip";
		byte[] zipBytes = zipBytes(Map.of("first.pdf", PDF_A, "second.pdf", PDF_B));
		try (var in = new ByteArrayInputStream(zipBytes)) {
			this.storage.put(objectKey, in, zipBytes.length, "application/zip");
		}
		String sha256 = sha256Hex(zipBytes);

		UUID storageObjectId = UUID.randomUUID();
		this.jdbc.update("INSERT INTO storage_object (id, object_key, original_filename, content_type, "
				+ "byte_size, sha256, created_at_epoch_ms) VALUES (?,?,?,?,?,?,?)", storageObjectId.toString(),
				objectKey, "EREF-OCR-E2E.zip", "application/zip", zipBytes.length, sha256,
				occurredAt.toEpochMilli());
		this.jdbc.update("INSERT INTO pos_record (id, source_archive_id, status, uploaded_by, "
				+ "uploaded_at_epoch_ms, updated_at_epoch_ms, version) VALUES (?,?,?,?,?,?,?)",
				posRecordId.toString(), storageObjectId.toString(), "UPLOADED", "test-uploader",
				occurredAt.toEpochMilli(), occurredAt.toEpochMilli(), 0L);
		this.jdbc.update("INSERT INTO ingestion_job (id, pos_record_id, status, attempt_count, "
				+ "created_at_epoch_ms, version) VALUES (?,?,?,?,?,?)", jobId.toString(),
				posRecordId.toString(), "QUEUED", 0L, occurredAt.toEpochMilli(), 0L);

		// Publish the ingestion message.
		send(jobId, posRecordId, eventId, occurredAt);

		// Wait for the job to reach COMPLETED.
		AtomicReference<String> finalJobStatus = new AtomicReference<>();
		await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(250)).until(() -> {
			String s = this.jdbc.queryForObject("SELECT status FROM ingestion_job WHERE id = ?", String.class,
					jobId.toString());
			finalJobStatus.set(s);
			return "COMPLETED".equals(s) || "FAILED".equals(s);
		});
		assertEquals("COMPLETED", finalJobStatus.get(), "Job must reach COMPLETED");

		// 1. Two pos_document rows exist.
		int docCount = this.jdbc.queryForObject("SELECT count(*) FROM pos_document WHERE pos_record_id = ?",
				Integer.class, posRecordId.toString());
		assertEquals(2, docCount, "Two pos_document rows must exist");

		// 2. Two storage objects for the PDFs exist.
		int pdfStorageCount = this.jdbc.queryForObject(
				"SELECT count(*) FROM storage_object WHERE object_key LIKE ?", Integer.class,
				"documents/" + posRecordId + "/%");
		assertEquals(2, pdfStorageCount, "Two PDF storage objects must exist");

		// 3. The fake OCR server received exactly two requests.
		assertEquals(2, ocrStub.getRequestCount(), "OCR server must receive exactly two requests");

		// 4. Two version-1 OCR results are stored with the expected text.
		int ocrResultCount = this.jdbc.queryForObject(
				"SELECT count(*) FROM document_ocr_result WHERE prompt_version = 1", Integer.class);
		assertEquals(2, ocrResultCount, "Two version-1 OCR results must exist");

		String ocrText = this.jdbc.queryForObject(
				"SELECT ocr_text FROM document_ocr_result WHERE prompt_version = 1 LIMIT 1", String.class);
		assertEquals(SYNTHETIC_OCR_TEXT, ocrText, "OCR text must match the synthetic text");

		// 5. Both documents become COMPLETED.
		int completedDocs = this.jdbc.queryForObject(
				"SELECT count(*) FROM pos_document WHERE pos_record_id = ? AND processing_status = 'COMPLETED'",
				Integer.class, posRecordId.toString());
		assertEquals(2, completedDocs, "Both documents must be COMPLETED");

		// 6. The ingestion job is COMPLETED.
		assertEquals("COMPLETED", this.jdbc.queryForObject(
				"SELECT status FROM ingestion_job WHERE id = ?", String.class, jobId.toString()));

		// 7. The POS record becomes REVIEW_REQUIRED, not COMPLETED.
		String recordStatus = this.jdbc.queryForObject("SELECT status FROM pos_record WHERE id = ?", String.class,
				posRecordId.toString());
		assertEquals("REVIEW_REQUIRED", recordStatus, "POS record must be REVIEW_REQUIRED");

		// 8. Redelivering the same message performs zero additional OCR
		//    requests and creates no duplicate rows.
		int ocrCountBeforeRedelivery = ocrStub.getRequestCount();
		int ocrResultCountBeforeRedelivery = this.jdbc.queryForObject(
				"SELECT count(*) FROM document_ocr_result WHERE prompt_version = 1", Integer.class);

		send(jobId, posRecordId, UUID.randomUUID(), occurredAt);

		// Deterministic ACK evidence: wait until both the ready AND
		// unacknowledged counts reach zero (proving the redelivery was
		// fully consumed and ACKed, not just sitting unacknowledged).
		// RabbitMQ Channel.messageCount() reports only ready messages;
		// getQueueProperties() reports both ready and unacknowledged.
		await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250)).until(() -> {
			long[] counts = queueReadyAndUnacked(this.rabbitTemplate, topology.queue());
			if (counts[0] != 0 || counts[1] != 0) {
				return false;
			}
			// Both ready and unacknowledged are zero; verify the job is
			// still COMPLETED (the redelivery was consumed and the
			// idempotent no-op completed without changing the job state).
			String s = this.jdbc.queryForObject("SELECT status FROM ingestion_job WHERE id = ?", String.class,
					jobId.toString());
			return "COMPLETED".equals(s);
		});

		assertEquals(ocrCountBeforeRedelivery, ocrStub.getRequestCount(),
				"Redelivery must perform zero additional OCR requests");
		assertEquals(ocrResultCountBeforeRedelivery, this.jdbc.queryForObject(
				"SELECT count(*) FROM document_ocr_result WHERE prompt_version = 1", Integer.class),
				"Redelivery must create no duplicate OCR results");

		// 9. No temporary rendered files remain in the test-owned temp
		//    directory after the workflow finishes. The PdfFirstPageRenderer
		//    and StoredPdfMaterializer use the injected TempFileFactory
		//    (see TestRenderConfig) which writes to a test-owned directory
		//    created at bean initialization.
		Path testDir = this.testTempFileFactory.createTempFile("probe", ".probe");
		Files.deleteIfExists(testDir);
		Path parentDir = testDir.getParent();
		try (var stream = Files.list(parentDir)) {
			long fileCount = stream.count();
			assertEquals(0, fileCount,
					"Test-owned render temp directory must be empty after processing");
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

	private static long queueDepth(RabbitTemplate template, String queue) {
		try {
			com.rabbitmq.client.Connection conn = template.getConnectionFactory().createConnection().getDelegate();
			com.rabbitmq.client.Channel ch = conn.createChannel();
			long count = ch.messageCount(queue);
			ch.close();
			return count;
		}
		catch (Exception e) {
			throw new AssertionError("queue depth check failed", e);
		}
	}

	/**
	 * Returns both the ready and unacknowledged message counts for a queue
	 * using the RabbitMQ management HTTP API.
	 * Index 0 = messages_ready, index 1 = messages_unacknowledged.
	 */
	private static long[] queueReadyAndUnacked(RabbitTemplate template, String queue) {
		try {
			int mgmtPort = rabbit.getMappedPort(15672);
			java.net.URL url = new java.net.URL(
					"http://" + rabbit.getHost() + ":" + mgmtPort + "/api/queues/%2F/" + java.net.URLEncoder.encode(queue, java.nio.charset.StandardCharsets.UTF_8));
			java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
			conn.setRequestMethod("GET");
			conn.setRequestProperty("Authorization",
					"Basic " + java.util.Base64.getEncoder().encodeToString(
							(rabbit.getAdminUsername() + ":" + rabbit.getAdminPassword()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
			if (conn.getResponseCode() != 200) {
				throw new AssertionError("Management API returned " + conn.getResponseCode());
			}
			String body = new String(conn.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
			long ready = parseJsonLong(body, "messages_ready");
			long unacked = parseJsonLong(body, "messages_unacknowledged");
			return new long[] { ready, unacked };
		}
		catch (AssertionError e) {
			throw e;
		}
		catch (Exception e) {
			throw new AssertionError("queue ready/unacked check failed", e);
		}
	}

	private static long parseJsonLong(String json, String key) {
		int idx = json.indexOf("\"" + key + "\"");
		if (idx < 0) {
			return 0;
		}
		int colonIdx = json.indexOf(':', idx);
		int endIdx = colonIdx + 1;
		while (endIdx < json.length() && (Character.isDigit(json.charAt(endIdx)) || json.charAt(endIdx) == ' ')) {
			endIdx++;
		}
		return Long.parseLong(json.substring(colonIdx + 1, endIdx).trim());
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

	/**
	 * Overrides the rendering beans to use a test-owned temp directory
	 * for temp file creation. The directory is created at bean
	 * initialization and deleted when the context is closed.
	 */
	@TestConfiguration
	static class TestRenderConfig {

		private Path testTempDir;

		@Bean
		@Primary
		TempFileFactory testTempFileFactory() throws java.io.IOException {
			this.testTempDir = Files.createTempDirectory("pos-doc-e2e-render-");
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
				horse.sumomo.pos_doc_backend.infrastructure.minio.MinioObjectStorage storage,
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
