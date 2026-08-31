package horse.sumomo.pos_doc_backend.ingestion.consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;

import horse.sumomo.pos_doc_backend.ingestion.messaging.IngestionRequestedMessage;

/**
 * Terminal transition applied when the listener gives up on a message.
 * Records the identifiable job (and its active POS record) as
 * {@code FAILED} in a fresh short transaction, then throws the
 * original exception so the listener container rejects the message
 * without requeue and the broker delivers it to the configured DLQ.
 *
 * <p>The recoverer is invoked directly by {@link IngestionListener}
 * (not through a Spring AMQP retry interceptor) so the FAILED
 * transition is always paired with the DLQ-bound message. The class
 * is named "recoverer" because it implements the same contract a
 * {@code MessageRecoverer} would: a fresh transaction to record the
 * terminal state, then re-throw.
 */
@Component
public class IngestionTerminalRecoverer {

	private static final Logger log = LoggerFactory.getLogger(IngestionTerminalRecoverer.class);

	private final IngestionMessageValidator validator;
	private final IngestionTerminalFailureService terminalFailureService;

	public IngestionTerminalRecoverer(IngestionMessageValidator validator,
			IngestionTerminalFailureService terminalFailureService) {
		this.validator = validator;
		this.terminalFailureService = terminalFailureService;
	}

	/**
	 * Records the FAILED transition for the identifiable job (and
	 * its active POS record) and returns the original exception
	 * unwrapped so the listener container can re-throw it.
	 */
	public void recover(Message message, Throwable cause) {
		IngestionConsumerService.IngestionMessageIdentifiers ids = null;
		ConsumerException categorized = categorize(cause);
		try {
			IngestionRequestedMessage parsed = this.validator.validate(message);
			ids = new IngestionConsumerService.IngestionMessageIdentifiers(parsed.jobId(), parsed.posRecordId());
		}
		catch (RuntimeException parseFailure) {
			ids = bestEffortIds(message);
		}
		if (ids != null) {
			this.terminalFailureService.markTerminal(ids, categorized);
		}
		else {
			log.warn("Retry exhausted but message identifiers could not be recovered (category=terminal-no-ids); "
					+ "message will be rejected to DLQ");
		}
	}

	private static ConsumerException categorize(Throwable cause) {
		if (cause instanceof ConsumerException ce) {
			return ce;
		}
		Throwable root = cause;
		while (root != null) {
			if (root instanceof ConsumerException ce) {
				return ce;
			}
			root = root.getCause();
		}
		return new ConsumerException(ConsumerException.Code.EXTRACTION_TRANSIENT_FAILURE, cause);
	}

	private static IngestionConsumerService.IngestionMessageIdentifiers bestEffortIds(Message message) {
		try {
			String correlation = message.getMessageProperties().getCorrelationId();
			String messageId = message.getMessageProperties().getMessageId();
			if (correlation == null || messageId == null) {
				return null;
			}
			return new IngestionConsumerService.IngestionMessageIdentifiers(
					java.util.UUID.fromString(correlation), java.util.UUID.fromString(messageId));
		}
		catch (RuntimeException e) {
			return null;
		}
	}
}