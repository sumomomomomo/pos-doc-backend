package horse.sumomo.pos_doc_backend.ingestion.application;

/**
 * Signals that the field-extraction backoff sleep was interrupted.
 *
 * <p>The real backoff restores the thread's interrupt flag before throwing this.
 * The workflow catches it and re-raises a retryable {@code ConsumerException}
 * (the categorized infrastructure path) so the interruption flows through the
 * consumer's bounded retry / terminal-recovery path instead of dead-lettering
 * without recovery.
 */
public class ExtractionBackoffInterruptionException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public ExtractionBackoffInterruptionException(Throwable cause) {
		super("interruption during field-extraction backoff", cause);
	}

}
