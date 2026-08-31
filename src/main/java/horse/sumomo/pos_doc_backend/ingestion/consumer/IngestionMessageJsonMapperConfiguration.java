package horse.sumomo.pos_doc_backend.ingestion.consumer;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Provides the dedicated {@link JsonMapper} used to parse
 * {@code INGESTION_REQUESTED} AMQP bodies.
 *
 * <p>Strict duplicate detection is enabled at the mapper level so that
 * a body which carries the same field name twice is rejected during
 * parsing rather than silently overwriting the first occurrence with
 * the second. The detector is wired through a unique
 * {@link Qualifier} ({@code ingestionMessageJsonMapper}) so it cannot
 * be confused with the mapper used elsewhere in the application (for
 * example by {@link horse.sumomo.pos_doc_backend.ingestion.application.PosArchiveIntakeService}).
 */
@Configuration
public class IngestionMessageJsonMapperConfiguration {

	/**
	 * Bean name for the ingestion-message Jackson mapper. The
	 * qualifier mirrors the bean name so {@code @Qualifier} lookups in
	 * tests and {@link IngestionMessageValidator} resolve this mapper
	 * explicitly.
	 */
	public static final String INGESTION_MESSAGE_JSON_MAPPER = "ingestionMessageJsonMapper";

	@Bean(name = INGESTION_MESSAGE_JSON_MAPPER)
	@Qualifier(INGESTION_MESSAGE_JSON_MAPPER)
	public JsonMapper ingestionMessageJsonMapper() {
		return JsonMapper.builder()
				.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
				.build();
	}
}