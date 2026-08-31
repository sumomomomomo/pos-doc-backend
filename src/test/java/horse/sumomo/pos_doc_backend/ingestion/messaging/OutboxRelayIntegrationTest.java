package horse.sumomo.pos_doc_backend.ingestion.messaging;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

import tools.jackson.databind.json.JsonMapper;

import horse.sumomo.pos_doc_backend.ingestion.api.OutboxRelayProperties;
import horse.sumomo.pos_doc_backend.ingestion.api.RabbitTopologyProperties;
import horse.sumomo.pos_doc_backend.persistence.entity.OutboxEventEntity;
import horse.sumomo.pos_doc_backend.persistence.repository.OutboxEventRepository;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real RabbitMQ + SQLite integration tests for the transactional-outbox relay
 * (Task 4-5, step 22).
 *
 * <p>Uses a real {@link RabbitMQContainer} and a real temporary SQLite
 * database. MinIO is not started (not needed for relay-only tests). The
 * container keeps its default {@code guest/guest} credentials (matching the
 * Spring Boot defaults), so the same {@code getAmqpUrl()} is used by both the
 * auto-configured {@link RabbitTemplate} and the test's assertion-only AMQP
 * connections. Automatic scheduling is effectively disabled by a large fixed
 * delay; the relay cycle is invoked directly through {@code relayOnce()}.
 * The test consumes the broker queue only for assertions; no production
 * listener is registered.
 */
@SpringBootTest(properties = {
 	"app.messaging.outbox.enabled=true",
 	"app.messaging.outbox.fixed-delay-ms=3600000",
 	"app.ingestion.consumer.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OutboxRelayIntegrationTest {

	private static final DockerImageName RABBIT_IMAGE = DockerImageName.parse("rabbitmq:4.3.5-management");

	// The guest user is restricted to localhost by RabbitMQ, so a Docker
	// bridge connection cannot use it; an admin user is created instead and
	// used by both Spring and the test's assertion-only connections.
	private static final String USER = "outbox-test-user";
	private static final String PASS = "outbox-test-secret-change-me";

	private static final String EXCHANGE = "pos.ingestion";
	private static final String QUEUE = "pos.ingestion.jobs";
	private static final String DLX = "pos.ingestion.dlx";
	private static final String DLQ = "pos.ingestion.dead";

	private static RabbitMQContainer rabbit;

	@Autowired
	private OutboxRelay relay;

	@Autowired
	private OutboxEventStateService stateService;

	@Autowired
	private OutboxEventRepository repository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private JsonMapper jsonMapper;

	@Autowired
	private RabbitTemplate rabbitTemplate;

	@Autowired
	private OutboxRelayProperties outboxProperties;

	@DynamicPropertySource
	static void containerProperties(DynamicPropertyRegistry registry) throws Exception {
		rabbit = new RabbitMQContainer(RABBIT_IMAGE)
				.withAdminUser(USER)
				.withAdminPassword(PASS);
		rabbit.start();

		// Docker Desktop on Windows can lag briefly in exposing a newly
		// published port; wait here (rabbit is assigned) until the AMQP port
		// accepts a connection before Spring tries to use it.
		try (Connection ignored = amqpWithRetry("startup")) {
			// reachable
		}

		// Use the container's own host/port (getHost/getAmqpPort are the
		// values Testcontainers' wait strategy validated) plus the admin
		// credentials created above.
		registry.add("spring.rabbitmq.host", rabbit::getHost);
		registry.add("spring.rabbitmq.port", rabbit::getAmqpPort);
		registry.add("spring.rabbitmq.username", () -> USER);
		registry.add("spring.rabbitmq.password", () -> PASS);

		Path sqliteDbFile = Files.createTempFile("pos-doc-outbox-test", ".db");
		sqliteDbFile.toFile().deleteOnExit();
		Path.of(sqliteDbFile.toString() + "-wal").toFile().deleteOnExit();
		Path.of(sqliteDbFile.toString() + "-shm").toFile().deleteOnExit();
		registry.add("SQLITE_URL", () -> "jdbc:sqlite:" + sqliteDbFile);
	}

	@AfterAll
	static void stopContainer() {
		if (rabbit != null && rabbit.isRunning()) {
			rabbit.stop();
		}
	}

	// ------------------------------------------------------------------
	// 1. topology declared
	// ------------------------------------------------------------------

	@Test
	@Order(1)
	void springDeclaresBothExchangesBothQueuesAndBothBindings() throws Exception {
		// Force the auto-configured connection so the RabbitAdmin declares the
		// beans; AMQP declares lazily on first connection, and nothing else in
		// this test has opened one yet.
		this.rabbitTemplate.execute(c -> null);

		try (Channel channel = openChannel()) {
			// Passive declare throws if the entity does not exist.
			channel.exchangeDeclarePassive(EXCHANGE);
			channel.exchangeDeclarePassive(DLX);
			channel.queueDeclarePassive(QUEUE);
			channel.queueDeclarePassive(DLQ);
		}

		assertTrue(managementBindings(EXCHANGE, QUEUE),
				"main exchange must be bound to the main queue");
		assertTrue(managementBindings(DLX, DLQ),
				"DLX must be bound to the dead-letter queue");
	}

	// ------------------------------------------------------------------
	// 2-6, 9, 10. happy path + idempotency
	// ------------------------------------------------------------------

	@Test
	@Order(2)
	void unpublishedEventIsPublishedAndMarkedPublished() throws Exception {
		UUID eventId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID posRecordId = UUID.randomUUID();
		Instant occurredAt = Instant.now();

		IngestionRequestedMessage message = IngestionRequestedMessage.of(eventId, jobId, posRecordId, occurredAt);
		String payloadJson = this.jsonMapper.writeValueAsString(message);
		insertOutboxEvent(eventId, jobId, payloadJson);

		this.relay.relayOnce();

		// 2/3/4: exactly one message on the real queue, body == stored payload,
		// with the required properties.
		try (Channel channel = openChannel()) {
			GetResponse response = boundedBasicGet(channel, QUEUE);
			assertNotNull(response, "the relay must publish the due event");
			assertArrayEquals(payloadJson.getBytes(StandardCharsets.UTF_8), response.getBody(),
					"received body must exactly match stored payload_json");
			AMQP.BasicProperties props = response.getProps();
			assertEquals(2, props.getDeliveryMode(), "message must be persistent");
			assertEquals(eventId.toString(), props.getMessageId());
			assertEquals(jobId.toString(), props.getCorrelationId());
			assertEquals("INGESTION_REQUESTED", props.getType());
			assertEquals("application/json", props.getContentType());
			assertEquals("UTF-8", props.getContentEncoding());
			// Consume-for-assertion only; requeue so nothing is lost.
			channel.basicNack(response.getEnvelope().getDeliveryTag(), false, true);
		}

		// 5/6: published stamp set, and the row is no longer pending.
		OutboxEventEntity saved = this.repository.findById(eventId).orElseThrow();
		assertNotNull(saved.getPublishedAt(), "a positive confirm must set publishedAt");
		assertTrue(this.repository.findPendingDue(Instant.now(), PageRequest.of(0, 100)).stream()
				.noneMatch(e -> e.getId().equals(eventId)), "published row must not be pending");
	}

	@Test
	@Order(3)
	void relayAgainAfterSuccessDoesNotRepublish() throws Exception {
		long before = queueMessageCount(QUEUE);
		this.relay.relayOnce();
		assertEquals(before, queueMessageCount(QUEUE), "a published event must not be republished");
	}

	// ------------------------------------------------------------------
	// 7. unroutable routing key + mandatory -> failure recorded
	// ------------------------------------------------------------------

	@Test
	@Order(4)
	void unroutableRoutingKeyLeavesRowUnpublishedAndIncrementsAttempt() throws Exception {
		UUID eventId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		String payloadJson = "{\"eventId\":\"" + eventId + "\"}";
		insertOutboxEvent(eventId, jobId, payloadJson);

		// A publisher wired to a routing key with no binding on the real
		// exchange: the broker accepts then returns the mandatory message.
		long messagesBefore = queueMessageCount(QUEUE);
		RabbitTopologyProperties badTopology = new RabbitTopologyProperties(EXCHANGE, "no.such.binding",
				QUEUE, DLX, "ingestion.dead", DLQ);
		RabbitOutboxPublisher unroutablePublisher = new RabbitOutboxPublisher(this.rabbitTemplate, badTopology,
				this.outboxProperties);
		boolean accepted = unroutablePublisher.publish(payloadJson.getBytes(StandardCharsets.UTF_8), eventId,
				jobId);
		assertEquals(false, accepted, "an unroutable mandatory publish must not be accepted");

		// Record the failure the way the relay would.
		this.stateService.recordFailure(eventId, Instant.now());
		OutboxEventEntity saved = this.repository.findById(eventId).orElseThrow();
		assertNull(saved.getPublishedAt(), "an unroutable event must remain unpublished");
		assertEquals(1, saved.getAttemptCount());
		assertEquals(messagesBefore, queueMessageCount(QUEUE), "no message should reach the queue");
	}

	// ------------------------------------------------------------------
	// 8. broker unavailable -> unpublished, retry advanced, job not failed
	// ------------------------------------------------------------------

	@Test
	@Order(5)
	void brokerUnavailableLeavesRowUnpublishedAndJobQueued() throws Exception {
		UUID eventId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID posRecordId = UUID.randomUUID();
		UUID storageId = UUID.randomUUID();
		String payloadJson = "{\"eventId\":\"" + eventId + "\"}";
		long now = System.currentTimeMillis();
		String sha = "a".repeat(64);

		// Satisfy the pos_record -> storage_object foreign key.
		this.jdbcTemplate.update(
				"INSERT INTO storage_object (id, object_key, original_filename, content_type, byte_size, "
						+ "sha256, created_at_epoch_ms) VALUES (?, ?, 'test.zip', 'application/zip', 1, ?, ?)",
				storageId.toString(), "archives/test/" + storageId + ".zip", sha, now);
		this.jdbcTemplate.update(
				"INSERT INTO pos_record (id, source_archive_id, status, uploaded_by, uploaded_at_epoch_ms, "
						+ "updated_at_epoch_ms, version) VALUES (?, ?, 'UPLOADED', 'AUTH_NOT_IMPLEMENTED', ?, ?, 0)",
				posRecordId.toString(), storageId.toString(), now, now);
		this.jdbcTemplate.update(
				"INSERT INTO ingestion_job (id, pos_record_id, status, attempt_count, created_at_epoch_ms, version) "
						+ "VALUES (?, ?, 'QUEUED', 0, ?, 0)",
				jobId.toString(), posRecordId.toString(), now);
		insertOutboxEvent(eventId, jobId, payloadJson);

		// Take the broker down, run a relay cycle, assert the row stayed
		// unpublished with an advanced retry, then restore the broker.
		rabbit.stop();
		try {
			this.relay.relayOnce();

			OutboxEventEntity saved = this.repository.findById(eventId).orElseThrow();
			assertNull(saved.getPublishedAt(), "an unavailable broker must not mark the event published");
			assertEquals(1, saved.getAttemptCount(), "the failed attempt must be recorded");
			Long nextAttempt = this.jdbcTemplate.queryForObject(
					"SELECT next_attempt_at_epoch_ms FROM outbox_event WHERE id = ?", Long.class, eventId.toString());
			Long createdAt = this.jdbcTemplate.queryForObject(
					"SELECT created_at_epoch_ms FROM outbox_event WHERE id = ?", Long.class, eventId.toString());
			assertTrue(nextAttempt > createdAt, "the retry timestamp must advance after a failure");

			// The ingestion job must not be marked failed.
			assertEquals("QUEUED", this.jdbcTemplate.queryForObject(
					"SELECT status FROM ingestion_job WHERE id = ?", String.class, jobId.toString()));
		}
		finally {
			restartBroker();
		}
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	private void insertOutboxEvent(UUID eventId, UUID jobId, String payloadJson) {
		long now = System.currentTimeMillis();
		this.jdbcTemplate.update(
				"INSERT INTO outbox_event (id, aggregate_type, aggregate_id, event_type, payload_json, "
						+ "created_at_epoch_ms, attempt_count, next_attempt_at_epoch_ms) VALUES "
						+ "(?, 'INGESTION_JOB', ?, 'INGESTION_REQUESTED', ?, ?, 0, ?)",
				eventId.toString(), jobId.toString(), payloadJson, now, now);
	}

	private static long queueMessageCount(String queue) throws Exception {
		try (Channel channel = openChannel()) {
			return channel.messageCount(queue);
		}
	}

	private static GetResponse boundedBasicGet(Channel channel, String queue) throws Exception {
		long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
		while (System.currentTimeMillis() < deadline) {
			GetResponse response = channel.basicGet(queue, false);
			if (response != null) {
				return response;
			}
			silentlySleep(100);
		}
		return null;
	}

	private static Channel openChannel() throws Exception {
		return amqpWithRetry("channel").createChannel();
	}

	/**
	 * Opens an AMQP connection, retrying with backoff. Docker Desktop on
	 * Windows can take a moment to expose a newly published container port,
	 * so a single immediate connect is flaky; a bounded retry makes the
	 * assertion-only connections reliable without arbitrary long sleeps.
	 */
	private static Connection amqpWithRetry(String clientTag) throws Exception {
		long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);
		long backoffMs = 250;
		Exception last = null;
		while (System.currentTimeMillis() < deadline) {
			// getAmqpUrl() is "amqp://<host>:<port>" (no credentials); insert
			// the admin credentials after the scheme.
			String url = rabbit.getAmqpUrl().replaceFirst("amqp://",
					"amqp://" + USER + ":" + PASS + "@");
			ConnectionFactory factory = new ConnectionFactory();
			factory.setUri(url);
			factory.setConnectionTimeout(5000);
			try {
				return factory.newConnection(clientTag);
			}
			catch (Exception e) {
				last = e;
				silentlySleep(backoffMs);
				backoffMs = Math.min(backoffMs * 2, 2000);
			}
		}
		throw new IllegalStateException("could not connect to the RabbitMQ test container", last);
	}

	private static boolean managementBindings(String source, String destination) throws Exception {
		// The management API requires the vhost segment (default "/", encoded
		// %2F) and a source/destination qualifier on the bindings endpoint.
		String url = rabbit.getHttpUrl().replaceAll("/$", "") + "/api/exchanges/%2F/" + source
				+ "/bindings/source";
		HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
		connection.setRequestMethod("GET");
		connection.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString(
				(USER + ":" + PASS).getBytes(StandardCharsets.UTF_8)));
		if (connection.getResponseCode() != 200) {
			return false;
		}
		String body = new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		return body.contains("\"destination\":\"" + destination + "\"");
	}

	private void restartBroker() throws Exception {
		rabbit.start();
		try (Connection ignored = amqpWithRetry("restart")) {
			// broker is reachable again
		}
	}

	private static void silentlySleep(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

}
