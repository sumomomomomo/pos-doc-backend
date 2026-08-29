package horse.sumomo.pos_doc_backend.infrastructure.minio;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real integration test of {@link MinioObjectStorage} against a Testcontainers
 * MinIO server.
 *
 * <p>The container is started from the {@link DynamicPropertySource} hook so
 * that it is guaranteed to be running before the Spring context (and its
 * MinIO properties) are created. If Docker is unavailable, Testcontainers
 * fails fast with a clear prerequisite message; the test never silently skips.
 *
 * <p>Cleanup stops the container and closes the {@link DataSource} (releasing
 * the SQLite file handle) before deleting the temporary database files. The
 * application context itself is deliberately left running: in this Spring
 * version, closing it here would cause the framework's own {@code afterTestClass}
 * listeners to try to restart the already-closed cached context and fail.
 */
@SpringBootTest
class MinioObjectStorageIntegrationTest {

	private static final String TEST_BUCKET = "pos-documents-test";
	private static final DockerImageName MINIO_IMAGE =
			DockerImageName.parse("minio/minio:RELEASE.2025-09-07T16-13-09Z");

	private static MinIOContainer minio;
	private static Path sqliteDbFile;
	private static DataSource dataSourceRef;

	@Autowired
	private DataSource dataSource;

	@Autowired
	private MinioObjectStorage storage;

	@DynamicPropertySource
	static void containerProperties(DynamicPropertyRegistry registry) throws Exception {
		minio = new MinIOContainer(MINIO_IMAGE)
				.withUserName("test-access-key")
				.withPassword("test-secret-key-change-me");
		minio.start();

		// Provision the test bucket with a bootstrap client before the Spring
		// context starts; the adapter under test never creates buckets.
		try (MinioClient bootstrap = MinioClient.builder()
				.endpoint(minio.getS3URL())
				.credentials(minio.getUserName(), minio.getPassword())
				.build()) {
			bootstrap.makeBucket(MakeBucketArgs.builder().bucket(TEST_BUCKET).build());
		}

		registry.add("storage.minio.endpoint", minio::getS3URL);
		registry.add("storage.minio.access-key", minio::getUserName);
		registry.add("storage.minio.secret-key", minio::getPassword);
		registry.add("storage.minio.bucket", () -> TEST_BUCKET);

		// Keep this test's context away from the developer's local database.
		sqliteDbFile = Files.createTempFile("pos-doc-minio-test", ".db");
		registry.add("SQLITE_URL", () -> "jdbc:sqlite:" + sqliteDbFile);
	}

	@BeforeEach
	void captureDataSource() {
		dataSourceRef = this.dataSource;
	}

	@AfterAll
	static void stopContainerAndRemoveTempDatabase() throws Exception {
		if (minio != null && minio.isRunning()) {
			minio.stop();
		}
		if (dataSourceRef instanceof Closeable closeable) {
			closeable.close();
		}
		Files.deleteIfExists(sqliteDbFile);
		Files.deleteIfExists(Path.of(sqliteDbFile.toString() + "-wal"));
		Files.deleteIfExists(Path.of(sqliteDbFile.toString() + "-shm"));
	}

	@Test
	void putThenGetReturnsIdenticalBytes() throws Exception {
		String objectKey = "test/integration/put-then-get.txt";
		byte[] content = "dummy-object-content".getBytes(StandardCharsets.UTF_8);

		try {
			this.storage.put(objectKey, new ByteArrayInputStream(content), content.length,
					"application/octet-stream");
			assertTrue(this.storage.exists(objectKey));

			byte[] read;
			try (InputStream in = this.storage.get(objectKey)) {
				read = in.readAllBytes();
			}
			assertArrayEquals(content, read);
		}
		finally {
			this.storage.delete(objectKey);
		}
	}

	@Test
	void deleteRemovesObject() {
		String objectKey = "test/integration/delete-" + System.nanoTime() + ".bin";
		byte[] content = {1, 2, 3, 4};

		this.storage.put(objectKey, new ByteArrayInputStream(content), content.length,
				"application/octet-stream");
		assertTrue(this.storage.exists(objectKey));

		this.storage.delete(objectKey);
		assertFalse(this.storage.exists(objectKey));
	}

	@Test
	void blankObjectKeyIsRejectedWithoutRemoteCall() {
		// Every public operation rejects a blank key locally, before any
		// remote call, with IllegalArgumentException.
		assertThrows(IllegalArgumentException.class, () ->
				this.storage.put(" ", new ByteArrayInputStream(new byte[0]), 0,
						"application/octet-stream"));
		assertThrows(IllegalArgumentException.class, () ->
				this.storage.put(null, new ByteArrayInputStream(new byte[0]), 0,
						"application/octet-stream"));
		assertThrows(IllegalArgumentException.class, () -> this.storage.get(""));
		assertThrows(IllegalArgumentException.class, () -> this.storage.get("   "));
		assertThrows(IllegalArgumentException.class, () -> this.storage.exists(" "));
		assertThrows(IllegalArgumentException.class, () -> this.storage.exists(null));
		assertThrows(IllegalArgumentException.class, () -> this.storage.delete(""));

		// put also rejects negative sizes and blank content types.
		assertThrows(IllegalArgumentException.class, () ->
				this.storage.put("test/negative-size", new ByteArrayInputStream(new byte[1]), -1,
						"application/octet-stream"));
		assertThrows(IllegalArgumentException.class, () ->
				this.storage.put("test/blank-content-type", new ByteArrayInputStream(new byte[1]), 1, " "));
		assertThrows(IllegalArgumentException.class, () ->
				this.storage.put("test/null-content-type", new ByteArrayInputStream(new byte[1]), 1, null));
	}

	@Test
	void connectionFailureIsNotReportedAsMissingObject() throws Exception {
		int unusedPort;
		try (ServerSocket probe = new ServerSocket(0)) {
			unusedPort = probe.getLocalPort();
		}

		MinioClient failingClient = MinioClient.builder()
				.endpoint("http://127.0.0.1:" + unusedPort)
				.credentials("dummy-access", "dummy-secret")
				.build();
		// Short timeouts keep the test prompt; no sleeps are needed.
		failingClient.setTimeout(1_000, 1_000, 1_000);

		MinioProperties properties = new MinioProperties("http://127.0.0.1:" + unusedPort,
				"dummy-access", "dummy-secret", TEST_BUCKET);
		MinioObjectStorage failingStorage = new MinioObjectStorage(failingClient, properties);

		try {
			// A network failure must surface as ObjectStorageException, not as
			// a "false" (missing object) result.
			assertThrows(ObjectStorageException.class, () -> failingStorage.exists("test/conn-failure"));
		}
		finally {
			failingClient.close();
		}
	}

}
