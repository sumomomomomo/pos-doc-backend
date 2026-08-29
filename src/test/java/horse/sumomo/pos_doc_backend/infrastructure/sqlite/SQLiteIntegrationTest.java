package horse.sumomo.pos_doc_backend.infrastructure.sqlite;

import java.io.Closeable;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test proving the real Spring context starts with the SQLite
 * {@link DataSource} and {@link SQLiteConfiguration} pragmas in effect.
 *
 * <p>A unique temporary database file is used via {@code SQLITE_URL}; the
 * developer's normal {@code pos-doc.db} is never touched. Before the temporary
 * database and its WAL/SHM side files are deleted, the {@link DataSource} is
 * closed so that its connection pool releases the SQLite file handle. The
 * application context itself is deliberately left running: in this Spring
 * version, closing it here would cause the framework's own {@code afterTestClass}
 * listeners to try to restart the already-closed cached context and fail.
 */
@SpringBootTest
class SQLiteIntegrationTest {

	private static Path dbFile;
	private static DataSource dataSourceRef;

	@Autowired
	private DataSource dataSource;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@DynamicPropertySource
	static void sqliteUrl(DynamicPropertyRegistry registry) throws Exception {
		dbFile = Files.createTempFile("pos-doc-sqlite-test", ".db");
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
	void contextStartsWithRealSqliteDataSource() {
		// The Task 1 exclusion of DataSourceAutoConfiguration is gone: the
		// context must contain a working data source bound to the temp file.
		assertNotNull(this.dataSource);
		String version = this.jdbcTemplate.queryForObject("SELECT sqlite_version()", String.class);
		assertNotNull(version);
		assertTrue(!version.isBlank(), "sqlite_version() must be non-blank");
	}

	@Test
	void startupPragmasAreInEffect() {
		Integer foreignKeys = this.jdbcTemplate.queryForObject("PRAGMA foreign_keys", Integer.class);
		assertEquals(1, foreignKeys);

		String journalMode = this.jdbcTemplate.queryForObject("PRAGMA journal_mode", String.class);
		assertEquals("wal", journalMode.toLowerCase());

		Integer busyTimeout = this.jdbcTemplate.queryForObject("PRAGMA busy_timeout", Integer.class);
		assertEquals(5000, busyTimeout);
	}

	@Test
	void canCreateTestTableAndRoundTripAValue() {
		this.jdbcTemplate.execute(
				"CREATE TABLE IF NOT EXISTS task2_probe (id INTEGER PRIMARY KEY, value TEXT NOT NULL)");
		this.jdbcTemplate.update("DELETE FROM task2_probe");
		this.jdbcTemplate.update("INSERT INTO task2_probe (value) VALUES (?)", "task2-probe-value");

		String value = this.jdbcTemplate.queryForObject("SELECT value FROM task2_probe LIMIT 1", String.class);
		assertEquals("task2-probe-value", value);

		this.jdbcTemplate.execute("DROP TABLE task2_probe");
	}

}
