package horse.sumomo.pos_doc_backend.ingestion.messaging;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import io.minio.MinioClient;
import io.minio.MakeBucketArgs;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

import horse.sumomo.pos_doc_backend.infrastructure.minio.MinioObjectStorage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack intake integration test (Task 4-5, step 24).
 *
 * <p>Uses a real Spring context, a real temporary SQLite database, real
 * Testcontainers MinIO and RabbitMQ, the real controller/service/repositories
 * and outbox relay, with {@link MockMvc} only as the HTTP client. The
 * automatic relay is effectively disabled by a large fixed delay and one
 * relay cycle is invoked directly.
 */
@SpringBootTest(properties = {
		"app.messaging.outbox.enabled=true",
		"app.messaging.outbox.fixed-delay-ms=3600000"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FullIntakeIntegrationTest {

	private static final DockerImageName MINIO_IMAGE =
			DockerImageName.parse("minio/minio:RELEASE.2025-09-07T16-13-09Z");
	private static final DockerImageName RABBIT_IMAGE =
			DockerImageName.parse("rabbitmq:4.3.5-management");

	private static final String MINIO_USER = "full-intake-access-key";
	private static final String MINIO_PASS = "full-intake-secret-change-me";
	private static final String RABBIT_USER = "full-intake-rabbit-user";
	private static final String RABBIT_PASS = "full-intake-rabbit-secret-change-me";
	private static final String TEST_BUCKET = "pos-documents-full-intake-test";

	private static final String QUEUE = "pos.ingestion.jobs";
	private static final byte[] PDF = "%PDF-1.4\n% dummy test document\n%%EOF\n".getBytes(StandardCharsets.UTF_8);

	private static MinIOContainer minio;
	private static MinioClient minioAdmin;
	private static RabbitMQContainer rabbit;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private MinioObjectStorage storage;

	@Autowired
	private OutboxRelay relay;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@DynamicPropertySource
	static void containerProperties(DynamicPropertyRegistry registry) throws Exception {
		minio = new MinIOContainer(MINIO_IMAGE)
				.withUserName(MINIO_USER)
				.withPassword(MINIO_PASS);
		minio.start();
		minioAdmin = MinioClient.builder()
				.endpoint(minio.getS3URL())
				.credentials(MINIO_USER, MINIO_PASS)
				.build();
		minioAdmin.makeBucket(MakeBucketArgs.builder().bucket(TEST_BUCKET).build());

		registry.add("storage.minio.endpoint", minio::getS3URL);
		registry.add("storage.minio.access-key", minio::getUserName);
		registry.add("storage.minio.secret-key", minio::getPassword);
		registry.add("storage.minio.bucket", () -> TEST_BUCKET);

		rabbit = new RabbitMQContainer(RABBIT_IMAGE)
				.withAdminUser(RABBIT_USER)
				.withAdminPassword(RABBIT_PASS);
		rabbit.start();
		try (Connection ignored = amqpWithRetry("startup")) {
			// AMQP port is ready before Spring uses it
		}
		registry.add("spring.rabbitmq.host", rabbit::getHost);
		registry.add("spring.rabbitmq.port", rabbit::getAmqpPort);
		registry.add("spring.rabbitmq.username", () -> RABBIT_USER);
		registry.add("spring.rabbitmq.password", () -> RABBIT_PASS);

		Path sqliteDbFile = Files.createTempFile("pos-doc-full-intake-test", ".db");
		sqliteDbFile.toFile().deleteOnExit();
		Path.of(sqliteDbFile.toString() + "-wal").toFile().deleteOnExit();
		Path.of(sqliteDbFile.toString() + "-shm").toFile().deleteOnExit();
		registry.add("SQLITE_URL", () -> "jdbc:sqlite:" + sqliteDbFile);
	}

	@AfterAll
	static void stopContainers() throws Exception {
		if (minio != null && minio.isRunning()) {
			minio.stop();
		}
		if (minioAdmin != null) {
			minioAdmin.close();
		}
		if (rabbit != null && rabbit.isRunning()) {
			rabbit.stop();
		}
	}

	@Test
	void endToEndIntakeStoresArchivePersistsRowsAndRelaysToQueue() throws Exception {
		byte[] zipBytes = zipBytes(Map.of("documents/first.pdf", PDF, "documents/second.pdf", PDF));
		MockMultipartFile file = new MockMultipartFile("file", "EREF-TASK45-001.zip", "application/zip", zipBytes);

		// 1-3. Submit the upload; expect 202, IDs, and a Location using posRecordId.
		MvcResult result = this.mockMvc.perform(multipart("/pos-records")
						.file(file)
						.param("policyNumber", "POLICY-TASK45-001"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("UPLOADED"))
				.andExpect(jsonPath("$.posRecordId").isNotEmpty())
				.andExpect(jsonPath("$.jobId").isNotEmpty())
				.andExpect(header().exists("Location"))
				.andReturn();

		String posRecordId = readJsonField(result, "posRecordId");
		String jobId = readJsonField(result, "jobId");
		String location = result.getResponse().getHeader("Location");
		assertTrue(location != null && location.endsWith("/pos-records/" + posRecordId),
				"Location must use the generated posRecordId");

		// 4. MinIO holds the archive byte-for-byte.
		String storageObjectId = this.jdbcTemplate.queryForObject(
				"SELECT source_archive_id FROM pos_record WHERE id = ?", String.class, posRecordId);
		String objectKey = this.jdbcTemplate.queryForObject(
				"SELECT object_key FROM storage_object WHERE id = ?", String.class, storageObjectId);
		assertTrue(objectKey.matches("archives/" + posRecordId + "/" + storageObjectId + "\\.zip"),
				"object key must be archives/{posRecordId}/{storageObjectId}.zip");
		byte[] stored = readAll(this.storage.get(objectKey));
		assertArrayEquals(zipBytes, stored, "MinIO must store the archive byte-for-byte");

		// 5. All expected rows exist; no pos_document yet.
		assertEquals(1, this.jdbcTemplate.queryForObject(
				"SELECT count(*) FROM storage_object WHERE id = ?", Integer.class, storageObjectId));
		assertEquals("UPLOADED", this.jdbcTemplate.queryForObject(
				"SELECT status FROM pos_record WHERE id = ?", String.class, posRecordId));
		assertEquals("QUEUED", this.jdbcTemplate.queryForObject(
				"SELECT status FROM ingestion_job WHERE id = ?", String.class, jobId));
		assertEquals(1, this.jdbcTemplate.queryForObject(
				"SELECT count(*) FROM outbox_event WHERE aggregate_id = ?", Integer.class, jobId));
		assertEquals(0, this.jdbcTemplate.queryForObject(
				"SELECT count(*) FROM pos_document WHERE pos_record_id = ?", Integer.class, posRecordId),
				"no pos_document rows in this task");
		String storedFilename = this.jdbcTemplate.queryForObject(
				"SELECT original_filename FROM storage_object WHERE id = ?", String.class, storageObjectId);
		assertEquals("EREF-TASK45-001.zip", storedFilename);

		// 6-8. Relay once; exactly one message, containing only the generated
		// identifiers, schema version, and timestamp (never the filename/key).
		this.relay.relayOnce();
		try (Channel channel = openChannel()) {
			assertEquals(1, channel.messageCount(QUEUE),
					"exactly one message must have been published");
			GetResponse response = boundedBasicGet(channel, QUEUE);
			assertNotNull(response, "the relay must publish the due event");
			String body = new String(response.getBody(), StandardCharsets.UTF_8);
			assertEquals(2, response.getProps().getDeliveryMode());
			assertFalse(body.contains("EREF-TASK45-001"), "message must not contain the eRef");
			assertFalse(body.contains(objectKey), "message must not contain the object key");
			assertFalse(body.contains("POLICY-TASK45-001"), "message must not contain the policy number");
			assertTrue(body.contains("\"eventId\""), "message must contain eventId");
			assertTrue(body.contains("\"jobId\":\"" + jobId + "\""), "message must contain the jobId");
			assertTrue(body.contains("\"posRecordId\":\"" + posRecordId + "\""), "message must contain the posRecordId");
			assertTrue(body.contains("\"schemaVersion\":1"), "message must contain schemaVersion 1");
			assertTrue(body.contains("\"occurredAt\""), "message must contain occurredAt");
			// Consume-for-assertion only; requeue so nothing is lost.
			channel.basicNack(response.getEnvelope().getDeliveryTag(), false, true);
		}

		// 9-10. Job still QUEUED via the real endpoint; outbox marked published.
		this.mockMvc.perform(get("/ingestion-jobs/{jobId}", jobId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("QUEUED"))
				.andExpect(jsonPath("$.posRecordId").value(posRecordId));

		Boolean publishedAtSet = this.jdbcTemplate.queryForObject(
				"SELECT published_at_epoch_ms IS NOT NULL FROM outbox_event WHERE aggregate_id = ?",
				Boolean.class, jobId);
		assertTrue(publishedAtSet, "the outbox row must be marked published");
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	private static byte[] zipBytes(Map<String, byte[]> entries) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(out)) {
			for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
				ZipEntry zipEntry = new ZipEntry(entry.getKey());
				zipEntry.setMethod(ZipEntry.DEFLATED);
				zip.putNextEntry(zipEntry);
				zip.write(entry.getValue());
				zip.closeEntry();
			}
		}
		return out.toByteArray();
	}

	private static String readJsonField(MvcResult result, String field) throws Exception {
		String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
		int idx = body.indexOf("\"" + field + "\":\"");
		int start = idx + field.length() + 4;
		int end = body.indexOf('"', start);
		return body.substring(start, end);
	}

	private static byte[] readAll(InputStream in) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		int n;
		while ((n = in.read(buffer)) != -1) {
			out.write(buffer, 0, n);
		}
		in.close();
		return out.toByteArray();
	}

	private static Channel openChannel() throws Exception {
		Connection connection = amqpWithRetry("channel");
		return connection.createChannel();
	}

	private static Connection amqpWithRetry(String clientTag) throws Exception {
		String url = rabbit.getAmqpUrl().replaceFirst("amqp://", "amqp://" + RABBIT_USER + ":" + RABBIT_PASS + "@");
		long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);
		long backoffMs = 250;
		Exception last = null;
		while (System.currentTimeMillis() < deadline) {
			ConnectionFactory factory = new ConnectionFactory();
			factory.setUri(url);
			factory.setConnectionTimeout(5000);
			try {
				return factory.newConnection(clientTag);
			}
			catch (Exception e) {
				last = e;
				Thread.sleep(backoffMs);
				backoffMs = Math.min(backoffMs * 2, 2000);
			}
		}
		throw new IllegalStateException("could not connect to the RabbitMQ test container", last);
	}

	private static GetResponse boundedBasicGet(Channel channel, String queue) throws Exception {
		long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
		while (System.currentTimeMillis() < deadline) {
			GetResponse response = channel.basicGet(queue, false);
			if (response != null) {
				return response;
			}
			Thread.sleep(100);
		}
		return null;
	}

}
