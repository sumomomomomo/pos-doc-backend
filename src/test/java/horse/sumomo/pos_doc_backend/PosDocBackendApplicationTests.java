package horse.sumomo.pos_doc_backend;

import java.io.Closeable;
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

/**
 * Application context test.
 *
 * <p>The context now starts with the real SQLite data source
 * ({@code DataSourceAutoConfiguration} is no longer excluded). A temporary
 * {@code SQLITE_URL} keeps the test away from the developer's normal
 * {@code pos-doc.db}. Starting the context does not require a running MinIO
 * server: the {@code MinioClient} bean is created without any network call,
 * and bucket provisioning happens outside normal application startup.
 *
 * <p>Cleanup closes the {@link DataSource} (releasing the SQLite file handle
 * so the temporary files can be deleted) and then removes the temporary
 * database and its WAL/SHM side files. The application context itself is
 * deliberately left running: in this Spring version, closing it here would
 * cause the framework's own {@code afterTestClass} listeners to try to restart
 * the already-closed cached context and fail.
 */
@SpringBootTest
class PosDocBackendApplicationTests {

	private static Path dbFile;
	private static DataSource dataSourceRef;

	@Autowired
	private DataSource dataSource;

	@DynamicPropertySource
	static void sqliteUrl(DynamicPropertyRegistry registry) throws Exception {
		dbFile = Files.createTempFile("pos-doc-context-test", ".db");
		registry.add("SQLITE_URL", () -> "jdbc:sqlite:" + dbFile);
	}

	@BeforeEach
	void captureDataSource() {
		dataSourceRef = this.dataSource;
	}

	@AfterAll
	static void releaseDatabaseAndRemoveTempFiles() throws Exception {
		if (dataSourceRef instanceof Closeable closeable) {
			closeable.close();
		}
		Files.deleteIfExists(dbFile);
		Files.deleteIfExists(Path.of(dbFile.toString() + "-wal"));
		Files.deleteIfExists(Path.of(dbFile.toString() + "-shm"));
	}

	@Test
	void contextLoads() {
	}

}
