package horse.sumomo.pos_doc_backend.ingestion.consumer;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import tools.jackson.databind.json.JsonMapper;

import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

import horse.sumomo.pos_doc_backend.ingestion.api.RabbitTopologyProperties;
import horse.sumomo.pos_doc_backend.ingestion.messaging.IngestionRequestedMessage;
import horse.sumomo.pos_doc_backend.infrastructure.minio.MinioObjectStorage;

/**
 * End-to-end Task 6 integration test: real RabbitMQ, real MinIO, real
 * SQLite. Uploads a ZIP via MinIO, persists the source metadata, then
 * publishes an {@link IngestionRequestedMessage} for the same job and
 * waits for the consumer to drain it. Asserts persisted documents,
 * MinIO objects, byte-for-byte equality, and DLQ emptiness.
 *
 * <p>The consumer is enabled; the outbox relay stays disabled because
 * the test publishes messages directly through the RabbitTemplate.
 */
@SpringBootTest(properties = {
		"app.messaging.outbox.enabled=false",
		"app.ingestion.consumer.enabled=true"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class IngestionConsumerIntegrationTest {

	private static final String TEST_BUCKET = "pos-documents-consumer-test";
	private static final DockerImageName MINIO_IMAGE =
			DockerImageName.parse("minio/minio:RELEASE.2025-09-07T16-13-09Z");

	private static final byte[] PDF_A = ("%PDF-1.4\n% Doc A\n%%EOF\n").getBytes(StandardCharsets.UTF_8);
	private static final byte[] PDF_B = ("%PDF-1.4\n% Doc B (longer)\n%%EOF\n").getBytes(StandardCharsets.UTF_8);

	private static MinIOContainer minio;
	private static RabbitMQContainer rabbit;
	private static MinioClient adminClient;

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
				.withUserName("consumer-access-key")
				.withPassword("consumer-secret-key-change-me");
		minio.start();
		adminClient = MinioClient.builder()
				.endpoint(minio.getS3URL())
				.credentials(minio.getUserName(), minio.getPassword())
				.build();
		adminClient.makeBucket(MakeBucketArgs.builder().bucket(TEST_BUCKET).build());

		rabbit = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.3.5-management"));
		rabbit.start();

		registry.add("storage.minio.endpoint", minio::getS3URL);
		registry.add("storage.minio.access-key", minio::getUserName);
		registry.add("storage.minio.secret-key", minio::getPassword);
		registry.add("storage.minio.bucket", () -> TEST_BUCKET);

		registry.add("spring.rabbitmq.host", rabbit::getHost);
		registry.add("spring.rabbitmq.port", rabbit::getAmqpPort);
		registry.add("spring.rabbitmq.username", rabbit::getAdminUsername);
		registry.add("spring.rabbitmq.password", rabbit::getAdminPassword);

		Path sqliteDbFile = Files.createTempFile("pos-doc-consumer-test", ".db");
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
	}

	@Test
	void consumerProcessesValidArchiveAndPersistsDocuments() throws Exception {
		UUID posRecordId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID eventId = UUID.randomUUID();
		Instant occurredAt = Instant.parse("2026-01-02T03:04:05Z");

		String objectKey = "archives/" + posRecordId + "/" + UUID.randomUUID() + ".zip";
		byte[] zipBytes = zipBytes(Map.of("first.pdf", PDF_A, "second.pdf", PDF_B));
		try (var in = new ByteArrayInputStream(zipBytes)) {
			this.storage.put(objectKey, in, zipBytes.length, "application/zip");
		}
		String sha256 = sha256Hex(zipBytes);

		UUID storageObjectId = UUID.randomUUID();
		this.jdbc.update("INSERT INTO storage_object (id, object_key, original_filename, content_type, "
				+ "byte_size, sha256, created_at_epoch_ms) VALUES (?,?,?,?,?,?,?)", storageObjectId.toString(),
				objectKey, "EREF-CONS.zip", "application/zip", zipBytes.length, sha256,
				occurredAt.toEpochMilli());
		this.jdbc.update("INSERT INTO pos_record (id, source_archive_id, status, uploaded_by, "
				+ "uploaded_at_epoch_ms, updated_at_epoch_ms, version) VALUES (?,?,?,?,?,?,?)",
				posRecordId.toString(), storageObjectId.toString(), "UPLOADED", "test-uploader",
				occurredAt.toEpochMilli(), occurredAt.toEpochMilli(), 0L);
		this.jdbc.update("INSERT INTO ingestion_job (id, pos_record_id, status, attempt_count, "
				+ "created_at_epoch_ms, version) VALUES (?,?,?,?,?,?)", jobId.toString(),
				posRecordId.toString(), "QUEUED", 0L, occurredAt.toEpochMilli(), 0L);

		IngestionRequestedMessage message = new IngestionRequestedMessage(eventId, jobId, posRecordId, 1, occurredAt);
		byte[] payload = this.json.writeValueAsBytes(message);
		MessageProperties props = new MessageProperties();
		props.setContentType("application/json");
		props.setContentEncoding("UTF-8");
		props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
		props.setType("INGESTION_REQUESTED");
		props.setMessageId(eventId.toString());
		props.setCorrelationId(jobId.toString());
		this.rabbitTemplate.send(topology.exchange(), topology.routingKey(),
				new Message(payload, props));

		AtomicReference<String> finalStatus = new AtomicReference<>();
		await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250)).until(() -> {
			String s = this.jdbc.queryForObject("SELECT status FROM ingestion_job WHERE id = ?", String.class,
					jobId.toString());
			finalStatus.set(s);
			return "COMPLETED".equals(s);
		});
		assertEquals("COMPLETED", finalStatus.get());

		java.util.List<Map<String, Object>> docs = this.jdbc.queryForList(
				"SELECT id, sequence_number, document_type, processing_status FROM pos_document "
						+ "WHERE pos_record_id = ? ORDER BY sequence_number ASC", posRecordId.toString());
		assertEquals(2, docs.size());
		assertEquals(0, ((Number) docs.get(0).get("sequence_number")).intValue());
		assertEquals(1, ((Number) docs.get(1).get("sequence_number")).intValue());
		assertEquals("UNKNOWN", docs.get(0).get("document_type"));
		assertEquals("PENDING", docs.get(0).get("processing_status"));

		String recordStatus = this.jdbc.queryForObject("SELECT status FROM pos_record WHERE id = ?", String.class,
				posRecordId.toString());
		assertEquals("PROCESSING", recordStatus);

		// Two PDFs are stored under deterministic UUID-only keys.
		java.util.List<String> keys = new java.util.ArrayList<>();
		Iterable<io.minio.Result<io.minio.messages.Item>> listing = adminClient.listObjects(ListObjectsArgs.builder()
				.bucket(TEST_BUCKET).prefix("documents/" + posRecordId + "/").build());
		for (io.minio.Result<io.minio.messages.Item> r : listing) {
			io.minio.messages.Item item = r.get();
			String key = item.objectName();
			assertTrue(key.matches("documents/[0-9a-f-]{36}/[0-9a-f-]{36}\\.pdf"), "key must be UUID-only");
			try (var in = adminClient.getObject(GetObjectArgs.builder().bucket(TEST_BUCKET).object(key).build());
					var out = new ByteArrayOutputStream()) {
				in.transferTo(out);
				byte[] body = out.toByteArray();
				if (body.length == PDF_A.length) {
					assertArrayEquals(PDF_A, body);
				}
				else if (body.length == PDF_B.length) {
					assertArrayEquals(PDF_B, body);
				}
				else {
					throw new AssertionError("unexpected PDF size: " + body.length);
				}
			}
			keys.add(key);
		}
		assertEquals(2, keys.size());

		assertEquals(0, queueDepth(this.rabbitTemplate, topology.queue()));
		assertEquals(0, queueDepth(this.rabbitTemplate, topology.deadLetterQueue()));
	}

	@Test
	void duplicateDeliveryIsIdempotent() throws Exception {
		// Same setup as the happy path: pre-create metadata, upload ZIP,
		// publish twice. The second delivery must be ACK'd as a no-op.
		UUID posRecordId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID eventId = UUID.randomUUID();
		UUID secondEventId = UUID.randomUUID();
		Instant occurredAt = Instant.parse("2026-01-02T03:04:05Z");

		String objectKey = "archives/" + posRecordId + "/" + UUID.randomUUID() + ".zip";
		byte[] zipBytes = zipBytes(Map.of("first.pdf", PDF_A, "second.pdf", PDF_B));
		try (var in = new ByteArrayInputStream(zipBytes)) {
			this.storage.put(objectKey, in, zipBytes.length, "application/zip");
		}
		String sha256 = sha256Hex(zipBytes);

		UUID storageObjectId = UUID.randomUUID();
		this.jdbc.update("INSERT INTO storage_object (id, object_key, original_filename, content_type, "
				+ "byte_size, sha256, created_at_epoch_ms) VALUES (?,?,?,?,?,?,?)", storageObjectId.toString(),
				objectKey, "EREF-DUP.zip", "application/zip", zipBytes.length, sha256,
				occurredAt.toEpochMilli());
		this.jdbc.update("INSERT INTO pos_record (id, source_archive_id, status, uploaded_by, "
				+ "uploaded_at_epoch_ms, updated_at_epoch_ms, version) VALUES (?,?,?,?,?,?,?)",
				posRecordId.toString(), storageObjectId.toString(), "UPLOADED", "test-uploader",
				occurredAt.toEpochMilli(), occurredAt.toEpochMilli(), 0L);
		this.jdbc.update("INSERT INTO ingestion_job (id, pos_record_id, status, attempt_count, "
				+ "created_at_epoch_ms, version) VALUES (?,?,?,?,?,?)", jobId.toString(),
				posRecordId.toString(), "QUEUED", 0L, occurredAt.toEpochMilli(), 0L);

		send(jobId, posRecordId, eventId, occurredAt);
		await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250)).until(() -> "COMPLETED"
				.equals(this.jdbc.queryForObject("SELECT status FROM ingestion_job WHERE id = ?", String.class,
						jobId.toString())));

		// Now publish a second message with a fresh event id but the same
		// job/pos record. The listener must ACK it without recreating rows.
		send(jobId, posRecordId, secondEventId, occurredAt);
		// Allow time for the no-op to complete.
		Thread.sleep(2000L);
		int docCount = this.jdbc.queryForObject("SELECT count(*) FROM pos_document WHERE pos_record_id = ?",
				Integer.class, posRecordId.toString());
		assertEquals(2, docCount, "duplicate delivery must not create additional documents");
		assertEquals("COMPLETED", this.jdbc.queryForObject("SELECT status FROM ingestion_job WHERE id = ?",
				String.class, jobId.toString()));
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
		this.rabbitTemplate.send(topology.exchange(), topology.routingKey(),
				new Message(payload, props));
	}

	private static long queueDepth(RabbitTemplate template, String queue) {
		try {
			com.rabbitmq.client.Connection conn = template.getConnectionFactory().createConnection()
					.getDelegate();
			com.rabbitmq.client.Channel ch = conn.createChannel();
			long count = ch.messageCount(queue);
			ch.close();
			return count;
		}
		catch (Exception e) {
			throw new AssertionError("queue depth check failed", e);
		}
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

	// Reserved: integration hooks for future scenarios (orphan recovery,
	// crash mid-extraction, etc.).
	@SuppressWarnings("unused")
	private static void unusedMarker() {
		assertNotNull(ByteArrayInputStream.class);
	}

}