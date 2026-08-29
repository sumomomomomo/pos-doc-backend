package horse.sumomo.pos_doc_backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The skeleton is HTTP-only and has no database configured, so the JDBC
 * datasource auto-configuration is excluded via
 * {@code spring.autoconfigure.exclude}.
 *
 * TODO: remove this exclusion when PostgreSQL and JPA are introduced.
 */
@SpringBootTest(properties = "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
class PosDocBackendApplicationTests {

	@Test
	void contextLoads() {
	}

}
