package horse.sumomo.pos_doc_backend.infrastructure.sqlite;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Applies SQLite-specific PRAGMAs on the configured data source at startup.
 *
 * <p>The primary mechanism is Hikari: the same PRAGMAs are supplied as JDBC
 * connection properties ({@code spring.datasource.hikari.data-source-properties})
 * in {@code application.yaml}, so every connection Hikari creates — including
 * replacement connections — gets the PRAGMAs applied. That is what guarantees
 * each connection is configured before it is handed out to any repository.
 *
 * <p>The {@link ApplicationReadyEvent} listener here performs an additional
 * startup verification/application pass once the application is ready. It is
 * not the mechanism guaranteeing pre-repository connection configuration:
 * {@code ApplicationReadyEvent} fires after the context has been refreshed,
 * by which point repositories could already have obtained connections.
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
