package horse.sumomo.pos_doc_backend.rendering.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import horse.sumomo.pos_doc_backend.ingestion.api.UploadLimitsProperties;

/**
 * Registers the rendering {@link FirstPageRenderingProperties} bean and
 * performs the cross-property validation that
 * {@code max-pdf-bytes} must not exceed the ingestion per-entry limit.
 *
 * <p>The validation runs at startup (when the {@link #init()} bean is
 * created) so a misconfiguration fails fast before any rendering attempt.
 */
@Configuration
@EnableConfigurationProperties(FirstPageRenderingProperties.class)
public class RenderingConfiguration {

	private static final Logger log = LoggerFactory.getLogger(RenderingConfiguration.class);

	@Bean
	public Object renderingPropertiesValidator(FirstPageRenderingProperties rendering,
			UploadLimitsProperties upload) {
		rendering.validateAgainstIngestionLimit(upload.maxEntryBytes());
		log.debug("Rendering properties validated; {}", rendering);
		return new Object();
	}

}
