package horse.sumomo.pos_doc_backend.ingestion.consumer;

import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.support.converter.SimpleMessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import horse.sumomo.pos_doc_backend.ingestion.api.ConsumerProperties;
import horse.sumomo.pos_doc_backend.ingestion.api.RabbitTopologyProperties;

/**
 * AMQP listener configuration: container factory with one consumer thread
 * and prefetch one, no manual immediate requeue. The listener itself
 * implements the bounded retry policy with a Thread.sleep back-off
 * between attempts.
 */
@Configuration
public class ConsumerConfiguration {

	/**
	 * Listener container factory used by {@link IngestionListener}. Tuned
	 * for the single-instance deployment: one consumer thread, prefetch
	 * one, and {@code defaultRequeueRejected=false} so a rejected message
	 * is dropped to the DLQ rather than infinitely requeued.
	 */
	@Bean
	@ConditionalOnProperty(prefix = "app.ingestion.consumer", name = "enabled", havingValue = "true")
	public SimpleRabbitListenerContainerFactory ingestionListenerContainerFactory(
			org.springframework.amqp.rabbit.connection.ConnectionFactory connectionFactory,
			ConsumerProperties consumerProperties) {
		SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
		factory.setConnectionFactory(connectionFactory);
		factory.setConcurrentConsumers(consumerProperties.getConcurrency());
		factory.setMaxConcurrentConsumers(consumerProperties.getConcurrency());
		factory.setPrefetchCount(consumerProperties.getPrefetch());
		factory.setDefaultRequeueRejected(false);
		factory.setMessageConverter(new SimpleMessageConverter());
		return factory;
	}

	/**
	 * Default recoverer: the original AMQP exception is logged with a
	 * stable category only, and the message is rejected without requeue
	 * so it lands on the DLQ. Retained for any framework-level use; the
	 * listener currently implements its own bounded retry, so this bean
	 * is unused at runtime but kept as a documented fallback.
	 */
	@Bean
	public MessageRecoverer ingestionRejectRecoverer() {
		return new RejectAndDontRequeueRecoverer();
	}

	/**
	 * Holds the queue name so the listener's {@code @RabbitListener}
	 * annotation can reference it through SpEL.
	 */
	@Bean
	@ConditionalOnProperty(prefix = "app.ingestion.consumer", name = "enabled", havingValue = "true")
	public IngestionQueueNameProvider ingestionQueueNameProvider(RabbitTopologyProperties topology) {
		return new IngestionQueueNameProvider(topology.queue());
	}

	/**
	 * Holder for the queue name so the listener annotation can reference
	 * it through SpEL.
	 */
	public static final class IngestionQueueNameProvider {

		private final String name;

		public IngestionQueueNameProvider(String name) {
			this.name = name;
		}

		public String getName() {
			return this.name;
		}
	}

}