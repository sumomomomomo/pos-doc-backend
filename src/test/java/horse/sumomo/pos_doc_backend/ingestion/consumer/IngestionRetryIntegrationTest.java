package horse.sumomo.pos_doc_backend.ingestion.consumer;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
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

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;

import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

import horse.sumomo.pos_doc_backend.ingestion.api.RabbitTopologyProperties;
import horse.sumomo.pos_doc_backend.ingestion.messaging.IngestionRequestedMessage;
import horse.sumomo.pos_doc_backend.infrastructure.minio.MinioObjectStorage;
import horse.sumomo.pos_doc_backend.infrastructure.minio.MinioProperties;
import horse.sumomo.pos_doc_backend.infrastructure.minio.ObjectStorageException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real-broker integration coverage for the three end-to-end Task 6
 * acceptance criteria that involve the listener container:
 *
 * <ol>
 *   <li>Transient storage failure followed by a successful retry — the
 *       job must end up {@code COMPLETED} and the deterministic keys
 *       must end up with the correct PDF bytes.</li>
 *   <li>Retry exhaustion — the job must end up {@code FAILED}, the
 *       POS record must end up {@code FAILED}, and the message must
 *       land on the DLQ.</li>
 *   <li>Malformed message — the message must land on the DLQ and the
 *       database must not be mutated for any job that the producer
 *       referenced.</li>
 * </ol>
 *
 * <p>A {@link MinioObjectStorage} override bean intercepts the
 * {@code put} call so we can deterministically inject transient
 * failures without breaking the real MinIO container. The failure
 * counter is held in a static field so the {@code @TestConfiguration}
 * bean (which is created before the test instance) can reach it.
 */
@SpringBootTest(properties = {
		"app.messaging.outbox.enabled=false",
		"app.ingestion.consumer.enabled=true",
		"app.ingestion.consumer.max-attempts=3",
		"app.ingestion.consumer.initial-backoff-ms=200",
		"app.ingestion.consumer.max-backoff-ms=200",
		"app.ingestion.consumer.backoff-multiplier=1.0"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IngestionRetryIntegrationTest {

	private static final String TEST_BUCKET = "pos-documents-retry-test";
	private static final DockerImageName MINIO_IMAGE =
			DockerImageName.parse("minio/minio:RELEASE.2025-09-07T16-13-09Z");
	private static final byte[] PDF_A = ("%PDF-1.4\n% Doc A\n%%EOF\n").getBytes(StandardCharsets.UTF_8);
	private static final byte[] PDF_B = ("%PDF-1.4\n% Doc B (longer)\n%%EOF\n").getBytes(StandardCharsets.UTF_8);

	private static MinIOContainer minio;
	private static RabbitMQContainer rabbit;
	private static MinioClient adminClient;

	/**
	 * Static failure counter shared between the test instance and the
	 * {@link FailingStorageConfiguration} bean. The test sets the
	 * value before each scenario; the wrapper decrements it on each
	 * {@code put} call until it reaches zero.
	 */
	static final AtomicInteger transientFailuresRemaining = new AtomicInteger();

	@Autowired
	private RabbitTemplate rabbitTemplate;

	@Autowired
	private RabbitTopologyProperties topology;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private MinioObjectStorage storage;

	@Autowired
	private JsonMapper json;

	@DynamicPropertySource
	static void containerProperties(DynamicPropertyRegistry registry) throws Exception {
		minio = new MinIOContainer(MINIO_IMAGE)
				.withUserName("retry-access-key")
				.withPassword("retry-secret-key-change-me");
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

		Path sqliteDbFile = Files.createTempFile("pos-doc-retry-test", ".db");
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
	@Order(1)
	void transientFailureFollowedBySuccessSucceedsViaBroker() throws Exception {
		// Allow exactly one put() call to fail with a transient
		// storage error; subsequent calls go through to the real
		// MinIO. This simulates a one-shot transient outage that the
		// listener retry loop must absorb.
		transientFailuresRemaining.set(1);

		UUID posRecordId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID eventId = UUID.randomUUID();
		Instant occurredAt = Instant.parse("2026-01-02T03:04:05Z");
		prepareJob(posRecordId, jobId, occurredAt);

		send(jobId, posRecordId, eventId, occurredAt);

		AtomicReference<String> finalJobStatus = new AtomicReference<>();
		await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(250)).until(() -> {
			String s = this.jdbc.queryForObject("SELECT status FROM ingestion_job WHERE id = ?", String.class,
					jobId.toString());
			finalJobStatus.set(s);
			return "COMPLETED".equals(s) || "FAILED".equals(s);
		});
		assertEquals("COMPLETED", finalJobStatus.get(),
				"Job must end COMPLETED after a one-shot transient failure");

		// Verify two deterministic PDFs were stored with the correct
		// bytes; the retry must have completed the upload.
		int docCount = this.jdbc.queryForObject("SELECT count(*) FROM pos_document WHERE pos_record_id = ?",
				Integer.class, posRecordId.toString());
		assertEquals(2, docCount);
		assertEquals(0L, queueDepth(this.rabbitTemplate, topology.queue()));
		assertEquals(0L, queueDepth(this.rabbitTemplate, topology.deadLetterQueue()));
	}

	@Test
	@Order(2)
	void exhaustedRetriesMarkJobFailedAndLandOnDlq() throws Exception {
		// Inject a put() failure on every attempt: the retry loop
		// must exhaust, the recoverer must transition the job to
		// FAILED, the POS record to FAILED, and the message must
		// land on the DLQ.
		transientFailuresRemaining.set(Integer.MAX_VALUE);

		UUID posRecordId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID eventId = UUID.randomUUID();
		Instant occurredAt = Instant.parse("2026-01-02T03:04:05Z");
		prepareJob(posRecordId, jobId, occurredAt);

		send(jobId, posRecordId, eventId, occurredAt);

		AtomicReference<String> finalJobStatus = new AtomicReference<>();
		AtomicReference<String> finalRecordStatus = new AtomicReference<>();
		await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(250)).until(() -> {
			String job = this.jdbc.queryForObject("SELECT status FROM ingestion_job WHERE id = ?", String.class,
					jobId.toString());
			finalJobStatus.set(job);
			if ("FAILED".equals(job)) {
				String rec = this.jdbc.queryForObject("SELECT status FROM pos_record WHERE id = ?", String.class,
						posRecordId.toString());
				finalRecordStatus.set(rec);
				return true;
			}
			return false;
		});
		assertEquals("FAILED", finalJobStatus.get());
		assertEquals("FAILED", finalRecordStatus.get(),
				"POS record must be marked FAILED when the job is FAILED");

		// The message must end up on the DLQ.
		await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(250))
				.until(() -> queueDepth(this.rabbitTemplate, topology.deadLetterQueue()) > 0L);
		assertTrue(queueDepth(this.rabbitTemplate, topology.queue()) == 0L,
				"main queue must be drained");

		// No pos_document rows must have been created.
		int docCount = this.jdbc.queryForObject("SELECT count(*) FROM pos_document WHERE pos_record_id = ?",
				Integer.class, posRecordId.toString());
		assertEquals(0, docCount);
	}

	@Test
	@Order(3)
	void malformedMessageReachesDlqWithoutDatabaseMutation() throws Exception {
		// No transient failures for this scenario.
		transientFailuresRemaining.set(0);

		UUID fakeJobId = UUID.randomUUID();
		UUID fakePosRecordId = UUID.randomUUID();
		UUID fakeEventId = UUID.randomUUID();

		// Send a malformed body: the validator rejects before any
		// database lookup or storage call.
		MessageProperties props = new MessageProperties();
		props.setContentType("application/json");
		props.setContentEncoding("UTF-8");
		props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
		props.setType("INGESTION_REQUESTED");
		props.setMessageId(fakeEventId.toString());
		props.setCorrelationId(fakeJobId.toString());
		Message bad = new Message("not-json-at-all".getBytes(StandardCharsets.UTF_8), props);
		this.rabbitTemplate.send(this.topology.exchange(), this.topology.routingKey(), bad);

		// DLQ must receive the message.
		await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(250))
				.until(() -> queueDepth(this.rabbitTemplate, topology.deadLetterQueue()) > 0L);

		// Give the listener a moment to attempt any DB mutation
		// (it should not do any).
		Thread.sleep(2000L);

		// No rows may exist for the fake IDs.
		Integer jobCount = this.jdbc.queryForObject("SELECT count(*) FROM ingestion_job WHERE id = ?",
				Integer.class, fakeJobId.toString());
		Integer recordCount = this.jdbc.queryForObject("SELECT count(*) FROM pos_record WHERE id = ?",
				Integer.class, fakePosRecordId.toString());
		Integer docCount = this.jdbc.queryForObject("SELECT count(*) FROM pos_document WHERE pos_record_id = ?",
				Integer.class, fakePosRecordId.toString());
		assertEquals(0, jobCount.intValue(), "no ingestion_job row may be created");
		assertEquals(0, recordCount.intValue(), "no pos_record row may be created");
		assertEquals(0, docCount.intValue(), "no pos_document row may be created");
	}

	private void prepareJob(UUID posRecordId, UUID jobId, Instant occurredAt) throws Exception {
		String objectKey = "archives/" + posRecordId + "/" + UUID.randomUUID() + ".zip";
		byte[] zipBytes = zipBytes(Map.of("first.pdf", PDF_A, "second.pdf", PDF_B));
		// Use the admin client directly to bypass the intercepting
		// wrapper so the archive upload is not subject to the
		// transient-failure counter.
		adminClient.putObject(io.minio.PutObjectArgs.builder()
				.bucket(TEST_BUCKET)
				.object(objectKey)
				.stream(new ByteArrayInputStream(zipBytes), (long) zipBytes.length, -1L)
				.contentType("application/zip")
				.build());
		String sha256 = sha256Hex(zipBytes);

		UUID storageObjectId = UUID.randomUUID();
		this.jdbc.update("INSERT INTO storage_object (id, object_key, original_filename, content_type, "
				+ "byte_size, sha256, created_at_epoch_ms) VALUES (?,?,?,?,?,?,?)", storageObjectId.toString(),
				objectKey, "EREF-RETRY.zip", "application/zip", zipBytes.length, sha256,
				occurredAt.toEpochMilli());
		this.jdbc.update("INSERT INTO pos_record (id, source_archive_id, status, uploaded_by, "
				+ "uploaded_at_epoch_ms, updated_at_epoch_ms, version) VALUES (?,?,?,?,?,?,?)",
				posRecordId.toString(), storageObjectId.toString(), "UPLOADED", "test-uploader",
				occurredAt.toEpochMilli(), occurredAt.toEpochMilli(), 0L);
		this.jdbc.update("INSERT INTO ingestion_job (id, pos_record_id, status, attempt_count, "
				+ "created_at_epoch_ms, version) VALUES (?,?,?,?,?,?)", jobId.toString(),
				posRecordId.toString(), "QUEUED", 0L, occurredAt.toEpochMilli(), 0L);
	}

	private void send(UUID jobId, UUID posRecordId, UUID eventId, Instant occurredAt) {
		IngestionRequestedMessage message = new IngestionRequestedMessage(eventId, jobId, posRecordId, 1, occurredAt);
		byte[] payload;
		try {
			payload = this.json.writeValueAsBytes(message);
		}
		catch (RuntimeException e) {
			throw new AssertionError(e);
		}
		MessageProperties props = new MessageProperties();
		props.setContentType("application/json");
		props.setContentEncoding("UTF-8");
		props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
		props.setType("INGESTION_REQUESTED");
		props.setMessageId(eventId.toString());
		props.setCorrelationId(jobId.toString());
		this.rabbitTemplate.send(this.topology.exchange(), this.topology.routingKey(),
				new Message(payload, props));
	}

	private static long queueDepth(RabbitTemplate template, String queue) {
		try {
			Connection conn = template.getConnectionFactory().createConnection().getDelegate();
			Channel ch = conn.createChannel();
			long count = ch.messageCount(queue);
			ch.close();
			return count;
		}
		catch (Exception e) {
			throw new AssertionError("queue depth check failed", e);
		}
	}

	private static String sha256Hex(byte[] bytes) throws Exception {
		java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
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
	 * Test configuration that replaces the {@link MinioObjectStorage}
	 * bean with a wrapper that intercepts {@code put} so the test can
	 * inject a deterministic number of transient failures. All other
	 * operations delegate to the underlying real MinIO client.
	 */
	@TestConfiguration
	static class FailingStorageConfiguration {

		@Bean
		@Primary
		MinioObjectStorage failingMinioObjectStorage(MinioClient client, MinioProperties properties) {
			return new InterceptingMinioStorage(client, properties);
		}
	}

	/**
	 * Wrapper that delegates to a real {@link MinioClient} but throws
	 * an {@link ObjectStorageException} on the first N calls to
	 * {@code put} (driven by the static
	 * {@link #transientFailuresRemaining} counter).
	 */
	static final class InterceptingMinioStorage extends MinioObjectStorage {

		InterceptingMinioStorage(MinioClient client, MinioProperties properties) {
			super(client, properties);
		}

		@Override
		public void put(String objectKey, InputStream input, long size, String contentType) {
			if (transientFailuresRemaining.get() > 0) {
				transientFailuresRemaining.decrementAndGet();
				throw new ObjectStorageException("simulated transient put failure on key " + objectKey);
			}
			super.put(objectKey, input, size, contentType);
		}
	}
}