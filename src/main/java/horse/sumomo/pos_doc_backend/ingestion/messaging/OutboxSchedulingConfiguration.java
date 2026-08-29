package horse.sumomo.pos_doc_backend.ingestion.messaging;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import horse.sumomo.pos_doc_backend.ingestion.api.OutboxRelayProperties;
import horse.sumomo.pos_doc_backend.ingestion.api.RabbitTopologyProperties;
import horse.sumomo.pos_doc_backend.ingestion.api.UploadLimitsProperties;

/**
 * Enables scheduled execution and registers the ingestion
 * {@code @ConfigurationProperties} beans.
 *
 * <p>Scheduling is enabled unconditionally (it is cheap when nothing is
 * scheduled); the {@link OutboxRelay} bean itself is only created when
 * {@code app.messaging.outbox.enabled=true}. The three properties types are
 * bound here because they live outside the existing
 * {@code @ConfigurationPropertiesScan} base package.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({UploadLimitsProperties.class, RabbitTopologyProperties.class,
		OutboxRelayProperties.class})
public class OutboxSchedulingConfiguration {

}
