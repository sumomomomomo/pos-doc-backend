package horse.sumomo.pos_doc_backend.ingestion.messaging;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import horse.sumomo.pos_doc_backend.ingestion.api.OutboxRelayProperties;
import horse.sumomo.pos_doc_backend.ingestion.api.RabbitTopologyProperties;

/**
 * Publishes one stored outbox payload to RabbitMQ and reports whether the
 * broker durably accepted and routed it.
 *
 * <p>Uses the managed Spring AMQP correlated-confirm mechanism: the message
 * is sent through the auto-configured {@link RabbitTemplate} (publisher
 * confirms are {@code correlated} and the template is {@code mandatory} via
 * {@code spring.rabbitmq}) together with a {@link CorrelationData} whose
 * future resolves to the {@code Confirm} for this exact message. A
 * {@code true} result requires both a positive (ack) confirm within the
 * configured timeout and the absence of a mandatory return. Any other
 * outcome — nack, timeout, return, or a broker failure — reports
 * {@code false}, so the caller keeps the event unpublished and retries.
 *
 * <p>Broker exception messages are never logged or propagated: they may
 * contain connection details or credentials. Only the stable failure
 * category is logged.
 */
@Component
public class RabbitOutboxPublisher implements OutboxPublisher {

	private static final Logger log = LoggerFactory.getLogger(RabbitOutboxPublisher.class);

	private static final String CONTENT_TYPE = "application/json";
	private static final String CONTENT_ENCODING = "UTF-8";
	private static final String MESSAGE_TYPE = "INGESTION_REQUESTED";

	private final RabbitTemplate rabbitTemplate;
	private final RabbitTopologyProperties topology;
	private final OutboxRelayProperties outbox;

	public RabbitOutboxPublisher(RabbitTemplate rabbitTemplate, RabbitTopologyProperties topology,
			OutboxRelayProperties outbox) {
		this.rabbitTemplate = rabbitTemplate;
		this.topology = topology;
		this.outbox = outbox;
	}

	@Override
	public boolean publish(byte[] payloadJson, UUID eventId, UUID jobId) {
		CorrelationData correlationData = new CorrelationData(eventId.toString());

		try {
			this.rabbitTemplate.send(topology.exchange(), topology.routingKey(),
					message(payloadJson, eventId, jobId), correlationData);
		}
		catch (Exception e) {
			// Never log the raw broker message; it may contain connection
			// details or credentials.
			log.debug("Outbox publish failed to send (category=send-failed); eventId={}", eventId);
			return false;
		}

		try {
			CorrelationData.Confirm confirm = correlationData.getFuture()
					.get(this.outbox.getConfirmTimeoutMs(), TimeUnit.MILLISECONDS);
			ReturnedMessage returned = correlationData.getReturned();
			boolean success = confirm != null && confirm.ack() && returned == null;
			if (!success) {
				// Stable category only; no broker exception text is logged.
				log.debug("Outbox publish not durably accepted (category=confirm-or-return); eventId={}",
						eventId);
			}
			return success;
		}
		catch (TimeoutException e) {
			log.debug("Outbox publish confirm timed out (category=confirm-timeout); eventId={}", eventId);
			return false;
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.debug("Outbox publish interrupted (category=interrupted); eventId={}", eventId);
			return false;
		}
		catch (ExecutionException e) {
			log.debug("Outbox publish confirm failed (category=confirm-error); eventId={}", eventId);
			return false;
		}
	}

	private Message message(byte[] payloadJson, UUID eventId, UUID jobId) {
		MessageProperties props = new MessageProperties();
		props.setContentType(CONTENT_TYPE);
		props.setContentEncoding(CONTENT_ENCODING);
		props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
		props.setMessageId(eventId.toString());
		props.setCorrelationId(jobId.toString());
		props.setType(MESSAGE_TYPE);
		props.setContentLength(payloadJson.length);
		return MessageBuilder.withBody(payloadJson).andProperties(props).build();
	}

}
