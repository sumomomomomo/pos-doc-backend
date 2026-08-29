package horse.sumomo.pos_doc_backend.ingestion.api;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed, validated names of the durable RabbitMQ topology.
 *
 * <p>Bound from {@code app.messaging.topology.*}. Every name must be
 * non-blank so the topology configuration fails fast at startup. The
 * properties deliberately hold only queue and exchange names; broker
 * credentials live under {@code spring.rabbitmq} and must never appear in
 * this type's {@link #toString()}.
 */
@ConfigurationProperties(prefix = "app.messaging.topology")
public final class RabbitTopologyProperties {

	private final String exchange;
	private final String routingKey;
	private final String queue;
	private final String deadLetterExchange;
	private final String deadLetterRoutingKey;
	private final String deadLetterQueue;

	public RabbitTopologyProperties(String exchange, String routingKey, String queue, String deadLetterExchange,
			String deadLetterRoutingKey, String deadLetterQueue) {
		this.exchange = requireNonBlank(exchange, "exchange");
		this.routingKey = requireNonBlank(routingKey, "routingKey");
		this.queue = requireNonBlank(queue, "queue");
		this.deadLetterExchange = requireNonBlank(deadLetterExchange, "deadLetterExchange");
		this.deadLetterRoutingKey = requireNonBlank(deadLetterRoutingKey, "deadLetterRoutingKey");
		this.deadLetterQueue = requireNonBlank(deadLetterQueue, "deadLetterQueue");
	}

	public String exchange() {
		return this.exchange;
	}

	public String routingKey() {
		return this.routingKey;
	}

	public String queue() {
		return this.queue;
	}

	public String deadLetterExchange() {
		return this.deadLetterExchange;
	}

	public String deadLetterRoutingKey() {
		return this.deadLetterRoutingKey;
	}

	public String deadLetterQueue() {
		return this.deadLetterQueue;
	}

	private static String requireNonBlank(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("app.messaging.topology." + name + " must not be blank");
		}
		return value.trim();
	}

	@Override
	public String toString() {
		return "RabbitTopologyProperties [exchange=" + this.exchange + ", routingKey=" + this.routingKey
				+ ", queue=" + this.queue + ", deadLetterExchange=" + this.deadLetterExchange
				+ ", deadLetterRoutingKey=" + this.deadLetterRoutingKey + ", deadLetterQueue="
				+ this.deadLetterQueue + "]";
	}

}
