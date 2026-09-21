package horse.sumomo.pos_doc_backend.ingestion.application;

/**
 * Injectable sleeper used between internal per-field retry attempts, so unit and
 * integration tests can substitute a zero-time (or recording) double for the
 * real bounded backoff.
 */
public interface ExtractionBackoff {

	/**
	 * Pauses for the given number of milliseconds.
	 *
	 * @param ms the non-negative delay in milliseconds
	 */
	void sleep(long ms);

	/**
	 * A real-time sleeper backed by {@link Thread#sleep(long)}. Interrupts are
	 * restored and surfaced as an {@link ExtractionBackoffInterruptionException}
	 * so the workflow can route them through the consumer's categorized
	 * retry / terminal-recovery path (preserving the interrupt flag).
	 */
	static ExtractionBackoff realTime() {
		return ms -> {
			if (ms < 0) {
				throw new IllegalArgumentException("ms must be non-negative");
			}
			try {
				Thread.sleep(ms);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new ExtractionBackoffInterruptionException(e);
			}
		};
	}

}
