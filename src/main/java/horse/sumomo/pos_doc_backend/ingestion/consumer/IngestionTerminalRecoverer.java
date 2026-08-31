package horse.sumomo.pos_doc_backend.ingestion.consumer;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;

import horse.sumomo.pos_doc_backend.ingestion.messaging.IngestionRequestedMessage;

/**
 * Terminal transition applied when the listener gives up on a message.
 * Records the identifiable job (and its active POS record) as
 * {@code FAILED} in a fresh short transaction, then re-throws the
 * original exception so the listener container rejects the message
 * without requeue and the broker delivers it to the configured DLQ.
 *
 * <p>The recoverer is invoked directly by {@link IngestionListener}
 * (not through a Spring AMQP retry interceptor) so the FAILED
 * transition is always paired with the DLQ-bound message. The class
 * is named "recoverer" because it implements the same contract a
 * {@code MessageRecoverer} would: a fresh transaction to record the
 * terminal state, then re-throw.
 *
 * <p><strong>Invariant:</strong> this recoverer never mutates a
 * database row based on identifiers recovered from an
 * un-validated message. It only trusts the {@code jobId} /
 * {@code posRecordId} pair produced by
 * {@link IngestionMessageValidator#validate(Message)} and, even
 * then, only after
 * {@link IngestionTerminalFailureService#verifyRelationship} has
 * confirmed the two are linked. The AMQP {@code messageId} header is
 * the {@code eventId}, not the {@code posRecordId}; this recoverer
 * never reads it for that purpose.
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
	 *
	 * <p>If the message body cannot be strictly validated, the
	 * recoverer treats the DLQ outcome as the only safe side-effect
	 * and skips the database mutation entirely.
	 */
	public void recover(Message message, Throwable cause) {
		ConsumerException categorized = categorize(cause);
		IngestionConsumerService.IngestionMessageIdentifiers ids = safeIdentifiers(message);
		if (ids == null) {
			log.warn("Retry exhausted but message identifiers could not be safely recovered "
					+ "(category=terminal-no-ids); message will be rejected to DLQ without DB mutation");
			return;
		}
		if (!this.terminalFailureService.verifyRelationship(ids.jobId(), ids.posRecordId())) {
			log.warn("Retry exhausted but jobId/posRecordId relationship could not be verified "
					+ "(category=terminal-unverified-ids); jobId={}; message will be rejected to DLQ "
					+ "without DB mutation", ids.jobId());
			return;
		}
		this.terminalFailureService.markTerminal(ids, categorized);
	}

	/**
	 * Returns {@code (jobId, posRecordId)} strictly from a validated
	 * body, or {@code null} if the body fails strict validation. The
	 * validator is the single source of truth; AMQP headers are
	 * intentionally not used here because {@code messageId} carries
	 * {@code eventId}, not {@code posRecordId}.
	 */
	private IngestionConsumerService.IngestionMessageIdentifiers safeIdentifiers(Message message) {
		try {
			IngestionRequestedMessage parsed = this.validator.validate(message);
			UUID jobId = parsed.jobId();
			UUID posRecordId = parsed.posRecordId();
			if (jobId == null || posRecordId == null) {
				return null;
			}
			return new IngestionConsumerService.IngestionMessageIdentifiers(jobId, posRecordId);
		}
		catch (RuntimeException parseFailure) {
			return null;
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
}