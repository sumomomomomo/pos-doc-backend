package horse.sumomo.pos_doc_backend.ingestion.consumer;

import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.SimpleMessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import horse.sumomo.pos_doc_backend.ingestion.api.ConsumerProperties;
import horse.sumomo.pos_doc_backend.ingestion.api.RabbitTopologyProperties;

/**
 * AMQP listener configuration.
 *
 * <p>Listener-container factory: one consumer thread, prefetch one,
 * {@code defaultRequeueRejected=false} so a rejected message is
 * dropped to the DLQ. The bounded retry policy itself is implemented
 * inside {@link IngestionListener} so the listener can invoke the
 * terminal recoverer directly when retries are exhausted.
 */
@Configuration
public class ConsumerConfiguration {

	/**
	 * Listener container factory used by {@link IngestionListener}. The
	 * listener concurrency is pinned to one thread; the prefetch is
	 * pinned to one. {@code defaultRequeueRejected=false} so a rejected
	 * message is sent to the DLQ rather than infinitely requeued. The
	 * retry interceptor is intentionally not installed here: the
	 * listener performs its own bounded retry so it can route the
	 * terminal transition (mark the job FAILED, mark the POS record
	 * FAILED, then reject) through a single audited code path.
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
	 * Default Spring AMQP recoverer: reject without requeue so the
	 * message lands on the DLQ. Kept as a documented fallback for
	 * cases that fall outside the listener's own retry loop.
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