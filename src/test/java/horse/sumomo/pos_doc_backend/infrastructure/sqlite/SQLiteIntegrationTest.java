package horse.sumomo.pos_doc_backend.infrastructure.sqlite;

import java.nio.file.Files;
import java.nio.file.Path;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
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
 * developer's normal {@code pos-doc.db} is never touched. The context is
 * closed by Spring itself via {@link DirtiesContext} after the class; the
 * test never closes the managed {@code DataSource} by hand. The temporary
 * database and its WAL/SHM side files are registered for deletion on JVM
 * exit instead of being force-removed before Spring has closed.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SQLiteIntegrationTest {

	@Autowired
	private DataSource dataSource;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@DynamicPropertySource
	static void sqliteUrl(DynamicPropertyRegistry registry) throws Exception {
		Path dbFile = Files.createTempFile("pos-doc-sqlite-test", ".db");
		dbFile.toFile().deleteOnExit();
		Path.of(dbFile.toString() + "-wal").toFile().deleteOnExit();
		Path.of(dbFile.toString() + "-shm").toFile().deleteOnExit();
		registry.add("SQLITE_URL", () -> "jdbc:sqlite:" + dbFile);
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
