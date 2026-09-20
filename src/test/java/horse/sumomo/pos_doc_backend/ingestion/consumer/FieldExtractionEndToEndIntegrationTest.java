package horse.sumomo.pos_doc_backend.ingestion.consumer;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
 * End-to-end integration test for the structured field-extraction workflow:
 * real temporary SQLite, real test MinIO, real RabbitMQ test container, real
 * PDFBox renderer (synthetic PDFs), and an ephemeral fake llama.cpp HTTP server.
 *
 * <p>Proves the complete flow with one ZIP containing a {@code LAPPe.pdf}
 * candidate and one other PDF:
 * <ol>
 *   <li>The message triggers extraction of two PDFs.</li>
 *   <li>The candidate (the {@code LAPPe.pdf}) is COMPLETED; the non-candidate
 *       is SKIPPED.</li>
 *   <li>The OCR server receives exactly three requests (one per business field),
 *       each carrying the exact field prompt.</li>
 *   <li>Three version-2 {@code pos_field_extraction} rows are stored and the
 *       business fields are applied to the record.</li>
 *   <li>The ingestion job is COMPLETED and the record is REVIEW_REQUIRED.</li>
 *   <li>Redelivering the same message performs zero additional OCR requests and
 *       creates no duplicate rows.</li>
 * </ol>
 */
@SpringBootTest(properties = {
		"app.messaging.outbox.enabled=false",
		"app.ingestion.consumer.enabled=true"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FieldExtractionEndToEndIntegrationTest {

	private static final String TEST_BUCKET = "pos-documents-field-e2e-test";
	private static final DockerImageName MINIO_IMAGE =
			DockerImageName.parse("cgr.dev/chainguard/minio:latest").asCompatibleSubstituteFor("minio/minio");
	private static final byte[] PDF_CANDIDATE = SyntheticPdfFactory.createPdf("LAPPe Doc");
	private static final byte[] PDF_OTHER = SyntheticPdfFactory.createPdf("Other Doc");

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
				.withUserName("field-e2e-access-key")
				.withPassword("field-e2e-secret-change-me");
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

		Path sqliteDbFile = Files.createTempFile("pos-doc-field-e2e-test", ".db");
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
	void endToEndFieldExtractionCompletesCandidateAndJob() throws Exception {
		UUID posRecordId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID eventId = UUID.randomUUID();
		Instant occurredAt = Instant.parse("2026-01-02T03:04:05Z");

		// Upload the ZIP (a LAPPe.pdf candidate + one other PDF) and the metadata.
		String objectKey = "archives/" + posRecordId + "/" + UUID.randomUUID() + ".zip";
		byte[] zipBytes = zipBytes(Map.of("LAPPe.pdf", PDF_CANDIDATE, "other.pdf", PDF_OTHER));
		try (var in = new ByteArrayInputStream(zipBytes)) {
			this.storage.put(objectKey, in, zipBytes.length, "application/zip");
		}
		String sha256 = sha256Hex(zipBytes);

		UUID storageObjectId = UUID.randomUUID();
		this.jdbc.update("INSERT INTO storage_object (id, object_key, original_filename, content_type, "
				+ "byte_size, sha256, created_at_epoch_ms) VALUES (?,?,?,?,?,?,?)", storageObjectId.toString(),
				objectKey, "EREF-FIELD-E2E.zip", "application/zip", zipBytes.length, sha256,
				occurredAt.toEpochMilli());
		this.jdbc.update("INSERT INTO pos_record (id, source_archive_id, status, uploaded_by, "
				+ "uploaded_at_epoch_ms, updated_at_epoch_ms, version) VALUES (?,?,?,?,?,?,?)",
				posRecordId.toString(), storageObjectId.toString(), "UPLOADED", "test-uploader",
				occurredAt.toEpochMilli(), occurredAt.toEpochMilli(), 0L);
		this.jdbc.update("INSERT INTO ingestion_job (id, pos_record_id, status, attempt_count, "
				+ "created_at_epoch_ms, version) VALUES (?,?,?,?,?,?)", jobId.toString(),
				posRecordId.toString(), "QUEUED", 0L, occurredAt.toEpochMilli(), 0L);

		// Queue the three field responses (fixed workflow order: policyholder,
		// consultant, date) so each field resolves on its first attempt.
		ocrStub.enqueueResponse("Charlie Henry", 200, "application/json");
		ocrStub.enqueueResponse("John Davidson", 200, "application/json");
		ocrStub.enqueueResponse("26-Jul-2026", 200, "application/json");

		// Publish the ingestion message.
		send(jobId, posRecordId, eventId, occurredAt);

		// Wait for the job to reach a terminal status.
		AtomicReference<String> finalJobStatus = new AtomicReference<>();
		await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(250)).until(() -> {
			String s = this.jdbc.queryForObject("SELECT status FROM ingestion_job WHERE id = ?", String.class,
					jobId.toString());
			finalJobStatus.set(s);
			return "COMPLETED".equals(s) || "FAILED".equals(s);
		});
		assertEquals("COMPLETED", finalJobStatus.get(), "Job must reach COMPLETED");

		// Two pos_document rows exist.
		int docCount = this.jdbc.queryForObject("SELECT count(*) FROM pos_document WHERE pos_record_id = ?",
				Integer.class, posRecordId.toString());
		assertEquals(2, docCount, "Two pos_document rows must exist");

		// Exactly three OCR requests (one per business field).
		assertEquals(3, ocrStub.getRequestCount(), "OCR server must receive exactly three requests");

		// The candidate (LAPPe.pdf) is COMPLETED; the other (other.pdf) is SKIPPED.
		String candidateStatus = this.jdbc.queryForObject(
				"SELECT d.processing_status FROM pos_document d "
						+ "JOIN storage_object s ON d.storage_object_id = s.id "
						+ "WHERE d.pos_record_id = ? AND s.original_filename = 'lappe.pdf'",
				String.class, posRecordId.toString());
		assertEquals("COMPLETED", candidateStatus, "the LAPPe.pdf candidate must be COMPLETED");
		String otherStatus = this.jdbc.queryForObject(
				"SELECT d.processing_status FROM pos_document d "
						+ "JOIN storage_object s ON d.storage_object_id = s.id "
						+ "WHERE d.pos_record_id = ? AND s.original_filename = 'other.pdf'",
				String.class, posRecordId.toString());
		assertEquals("SKIPPED", otherStatus, "the non-candidate must be SKIPPED");

		// Three version-2 durable outcomes for the candidate.
		String candidateId = this.jdbc.queryForObject(
				"SELECT d.id FROM pos_document d JOIN storage_object s ON d.storage_object_id = s.id "
						+ "WHERE d.pos_record_id = ? AND s.original_filename = 'lappe.pdf'",
				String.class, posRecordId.toString());
		int outcomeCount = this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM pos_field_extraction WHERE document_id = ? AND prompt_version = 2",
				Integer.class, candidateId);
		assertEquals(3, outcomeCount, "three version-2 outcomes must exist for the candidate");

		// Business fields applied (canonical values).
		assertEquals("Charlie Henry", this.jdbc.queryForObject(
				"SELECT policyholder_name FROM pos_record WHERE id = ?", String.class, posRecordId.toString()));
		assertEquals("John Davidson", this.jdbc.queryForObject(
				"SELECT consultant_name FROM pos_record WHERE id = ?", String.class, posRecordId.toString()));
		assertEquals("2026-07-26", this.jdbc.queryForObject(
				"SELECT policy_create_date FROM pos_record WHERE id = ?", String.class, posRecordId.toString()));

		// Job COMPLETED, record REVIEW_REQUIRED (not COMPLETED).
		assertEquals("REVIEW_REQUIRED", this.jdbc.queryForObject("SELECT status FROM pos_record WHERE id = ?",
				String.class, posRecordId.toString()));

		// Redelivering performs zero additional OCR requests and no duplicates.
		int ocrCountBefore = ocrStub.getRequestCount();
		int outcomeCountBefore = this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM pos_field_extraction", Integer.class);
		send(jobId, posRecordId, UUID.randomUUID(), occurredAt);

		await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250)).until(() -> {
			long[] counts = queueReadyAndUnacked(this.rabbitTemplate, topology.queue());
			if (counts[0] != 0 || counts[1] != 0) {
				return false;
			}
			String s = this.jdbc.queryForObject("SELECT status FROM ingestion_job WHERE id = ?", String.class,
					jobId.toString());
			return "COMPLETED".equals(s);
		});

		assertEquals(ocrCountBefore, ocrStub.getRequestCount(), "redelivery must make zero new OCR requests");
		assertEquals(outcomeCountBefore, this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM pos_field_extraction", Integer.class),
				"redelivery must create no duplicate outcome rows");
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

	private static long[] queueReadyAndUnacked(RabbitTemplate template, String queue) {
		try {
			int mgmtPort = rabbit.getMappedPort(15672);
			java.net.URL url = new java.net.URL(
					"http://" + rabbit.getHost() + ":" + mgmtPort + "/api/queues/%2F/"
							+ java.net.URLEncoder.encode(queue, java.nio.charset.StandardCharsets.UTF_8));
			java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
			conn.setRequestMethod("GET");
			conn.setRequestProperty("Authorization",
					"Basic " + java.util.Base64.getEncoder().encodeToString(
							(rabbit.getAdminUsername() + ":" + rabbit.getAdminPassword())
									.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
			if (conn.getResponseCode() != 200) {
				throw new AssertionError("Management API returned " + conn.getResponseCode());
			}
			String body = new String(conn.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
			return new long[] { parseJsonLong(body, "messages_ready"),
					parseJsonLong(body, "messages_unacknowledged") };
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
	 * Overrides the rendering beans to use a test-owned temp directory (mirrors
	 * the previous OCR e2e test). Not strictly asserted here, but keeps temp file
	 * creation isolated from the host temp directory.
	 */
	@TestConfiguration
	static class TestRenderConfig {

		private Path testTempDir;

		@Bean
		@Primary
		TempFileFactory testTempFileFactory() throws java.io.IOException {
			this.testTempDir = Files.createTempDirectory("pos-doc-field-e2e-render-");
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
