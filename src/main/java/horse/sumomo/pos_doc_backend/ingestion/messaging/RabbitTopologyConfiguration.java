package horse.sumomo.pos_doc_backend.ingestion.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import horse.sumomo.pos_doc_backend.ingestion.api.RabbitTopologyProperties;

/**
 * Declares the durable RabbitMQ topology for ingestion requests.
 *
 * <p>All names come from {@link RabbitTopologyProperties}; nothing is
 * repeated as a string literal. Exchanges and queues are durable, the main
 * queue is bound to the main exchange with the configured routing key, and
 * the dead-letter queue is bound to the DLX. The main queue carries the
 * DLX and dead-letter routing key as queue arguments, which is acceptable
 * for this single-node deployment.
 *
 * <p>No TTL, maximum queue length, auto-delete, exclusivity, fanout, or
 * default-exchange declarations are used, and no production consumer is
 * registered here.
 */
@Configuration
public class RabbitTopologyConfiguration {

	@Bean
	public DirectExchange ingestionExchange(RabbitTopologyProperties topology) {
		return new DirectExchange(topology.exchange(), true, false);
	}

	@Bean
	public DirectExchange ingestionDeadLetterExchange(RabbitTopologyProperties topology) {
		return new DirectExchange(topology.deadLetterExchange(), true, false);
	}

	@Bean
	public Queue ingestionQueue(RabbitTopologyProperties topology) {
		return QueueBuilder.durable(topology.queue())
				.withArgument("x-dead-letter-exchange", topology.deadLetterExchange())
				.withArgument("x-dead-letter-routing-key", topology.deadLetterRoutingKey())
				.build();
	}

	@Bean
	public Queue ingestionDeadLetterQueue(RabbitTopologyProperties topology) {
		return QueueBuilder.durable(topology.deadLetterQueue()).build();
	}

	@Bean
	public Binding ingestionQueueBinding(Queue ingestionQueue, DirectExchange ingestionExchange,
			RabbitTopologyProperties topology) {
		return BindingBuilder.bind(ingestionQueue).to(ingestionExchange).with(topology.routingKey());
	}

	@Bean
	public Binding ingestionDeadLetterBinding(Queue ingestionDeadLetterQueue,
			DirectExchange ingestionDeadLetterExchange, RabbitTopologyProperties topology) {
		return BindingBuilder.bind(ingestionDeadLetterQueue).to(ingestionDeadLetterExchange)
				.with(topology.deadLetterRoutingKey());
	}

}
