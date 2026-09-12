package horse.sumomo.pos_doc_backend.review;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the POS-record review feature.
 *
 * <p>Provides a {@link Clock} so {@code updatedAt}/{@code deletedAt} stamps are
 * taken from the server clock (not a client value or the host default timezone)
 * and can be fixed deterministically in tests. {@code Instant} is epoch-based,
 * so {@link Clock#systemUTC()} yields the same instants as any other zone.
 */
@Configuration
public class ReviewConfiguration {

	@Bean
	public Clock clock() {
		return Clock.systemUTC();
	}

}
