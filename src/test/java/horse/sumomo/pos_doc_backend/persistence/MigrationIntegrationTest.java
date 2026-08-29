package horse.sumomo.pos_doc_backend.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that Flyway — and only Flyway — owns the schema on a clean
 * temporary SQLite database. All checks read SQLite metadata through
 * {@link JdbcTemplate}; migration success is never inferred from the fact
 * that the Spring context started.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MigrationIntegrationTest {

	private static final Set<String> EXPECTED_TABLES = Set.of(
			"flyway_schema_history",
			"storage_object",
			"pos_record",
			"pos_document",
			"ingestion_job",
			"outbox_event");

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@DynamicPropertySource
	static void sqliteUrl(DynamicPropertyRegistry registry) throws Exception {
		Path dbFile = Files.createTempFile("pos-doc-migration-test", ".db");
		dbFile.toFile().deleteOnExit();
		Path.of(dbFile.toString() + "-wal").toFile().deleteOnExit();
		Path.of(dbFile.toString() + "-shm").toFile().deleteOnExit();
		registry.add("SQLITE_URL", () -> "jdbc:sqlite:" + dbFile);
	}

	@Test
	void flywayHistoryContainsExactlyTheSuccessfulMigrationsVersion1And2() {
		Boolean historyExists = this.jdbcTemplate.queryForObject(
				"SELECT count(*) > 0 FROM sqlite_master WHERE type = 'table' AND name = 'flyway_schema_history'",
				Boolean.class);
		assertTrue(historyExists, "flyway_schema_history table must exist");

		List<Integer> successfulVersions = this.jdbcTemplate.query(
				"SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank",
				(rs, rowNum) -> rs.getInt(1));
		assertEquals(List.of(1, 2), successfulVersions,
				"exactly migration versions 1 and 2 must be successful");
	}

	@Test
	void allFlywayTablesExistAndHibernateAddedNone() {
		Set<String> tables = tableNames();
		assertEquals(EXPECTED_TABLES, tables,
				"the database must contain exactly the Flyway schema; Hibernate must not add business tables");
	}

	@Test
	void allDeclaredForeignKeysAreEnabledAndPresent() {
		// Foreign key enforcement must be active on this (Hikari-configured)
		// connection, so the declared FKs are actually in force.
		Integer foreignKeysEnabled = this.jdbcTemplate.queryForObject("PRAGMA foreign_keys", Integer.class);
		assertEquals(1, foreignKeysEnabled);

		assertEquals(Set.of("source_archive_id->storage_object.id"), foreignKeysOf("pos_record"));
		assertEquals(Set.of("pos_record_id->pos_record.id", "storage_object_id->storage_object.id"),
				foreignKeysOf("pos_document"));
		assertEquals(Set.of("pos_record_id->pos_record.id"), foreignKeysOf("ingestion_job"));
		assertEquals(Set.of(), foreignKeysOf("storage_object"));

		// No stored row violates any foreign key.
		List<Map<String, Object>> violations = this.jdbcTemplate
				.queryForList("SELECT * FROM pragma_foreign_key_check()");
		assertEquals(List.of(), violations, "no row may violate a foreign key");
	}

	@Test
	void partialUniqueIndexesExistWithTheirActiveRecordPredicates() {
		List<Map<String, Object>> rows = this.jdbcTemplate.queryForList(
				"SELECT name, sql FROM sqlite_master WHERE type = 'index' "
						+ "AND name IN ('uq_pos_record_active_eref', 'uq_pos_record_active_policy')");
		assertEquals(2, rows.size(), "both partial unique indexes must exist");

		Set<String> names = new TreeSet<>();
		for (Map<String, Object> row : rows) {
			names.add(String.valueOf(row.get("name")));
			String sql = String.valueOf(row.get("sql"));
			assertTrue(sql.toUpperCase().contains("UNIQUE"), "index must be unique: " + sql);
			assertTrue(sql.contains("WHERE deleted_at_epoch_ms IS NULL"),
					"index must be the partial active-record predicate: " + sql);
		}
		assertEquals(Set.of("uq_pos_record_active_eref", "uq_pos_record_active_policy"), names);
	}

	private Set<String> tableNames() {
		Set<String> names = new TreeSet<>();
		this.jdbcTemplate.query(
				"SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
				(rs, rowNum) -> names.add(rs.getString(1)));
		return names;
	}

	private Set<String> foreignKeysOf(String table) {
		Set<String> fks = new TreeSet<>();
		this.jdbcTemplate.query("PRAGMA foreign_key_list(" + table + ")",
				(rs, rowNum) -> fks.add(rs.getString("from") + "->" + rs.getString("table") + "." + rs.getString("to")));
		return fks;
	}

}
