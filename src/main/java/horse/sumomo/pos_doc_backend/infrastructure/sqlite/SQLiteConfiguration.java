package horse.sumomo.pos_doc_backend.infrastructure.sqlite;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Applies SQLite-specific PRAGMAs on the configured data source at startup,
 * before any business repository can use a connection.
 *
 * <p>The same PRAGMAs are also supplied as JDBC connection properties in
 * {@code application.yaml} so that they are re-applied whenever Hikari creates
 * a replacement connection. The startup pass here additionally guarantees the
 * pragmas are in effect on the very first connection of the application.
 *
 * <p>No business tables are created here, and failures are not swallowed:
 * a SQL exception propagates and fails application startup.
 */
@Configuration
public class SQLiteConfiguration {

	private static final Logger log = LoggerFactory.getLogger(SQLiteConfiguration.class);

	private static final String[] STARTUP_PRAGMAS = {
			"PRAGMA foreign_keys = ON;",
			"PRAGMA journal_mode = WAL;",
			"PRAGMA busy_timeout = 5000;"
	};

	private final JdbcTemplate jdbcTemplate;

	public SQLiteConfiguration(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@EventListener(ApplicationReadyEvent.class)
	void applyStartupPragmas() {
		for (String pragma : STARTUP_PRAGMAS) {
			jdbcTemplate.execute(pragma);
		}
		log.debug("SQLite startup pragmas applied (foreign_keys, WAL, busy_timeout).");
	}

}
