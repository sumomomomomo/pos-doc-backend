package horse.sumomo.pos_doc_backend;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Application context test.
 *
 * <p>The context starts with the real SQLite data source. A temporary
 * {@code SQLITE_URL} keeps the test away from the developer's normal
 * {@code pos-doc.db}. Starting the context does not require a running MinIO
 * server: the {@code MinioClient} bean is created without any network call,
 * and bucket provisioning happens outside normal application startup.
 *
 * <p>The context is closed by Spring itself via {@link DirtiesContext} after
 * the class; the test never closes the managed {@code DataSource} by hand
 * (doing so while the context is still cached is a lifecycle bug). The
 * temporary database and its WAL/SHM side files are registered for deletion
 * on JVM exit instead of being force-removed before Spring has closed.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PosDocBackendApplicationTests {

	@DynamicPropertySource
	static void sqliteUrl(DynamicPropertyRegistry registry) throws Exception {
		Path dbFile = Files.createTempFile("pos-doc-context-test", ".db");
		dbFile.toFile().deleteOnExit();
		Path.of(dbFile.toString() + "-wal").toFile().deleteOnExit();
		Path.of(dbFile.toString() + "-shm").toFile().deleteOnExit();
		registry.add("SQLITE_URL", () -> "jdbc:sqlite:" + dbFile);
	}

	@Test
	void contextLoads() {
	}

}
