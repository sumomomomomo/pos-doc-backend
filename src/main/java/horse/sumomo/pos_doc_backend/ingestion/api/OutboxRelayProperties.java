package horse.sumomo.pos_doc_backend.ingestion.api;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tuning knobs for the transactional-outbox relay.
 *
 * <p>Bound from {@code app.messaging.outbox.*}. The relay is only created
 * when {@link #enabled} is true. All timeouts and delays must be positive.
 */
@ConfigurationProperties(prefix = "app.messaging.outbox")
public final class OutboxRelayProperties {

	private boolean enabled;
	private long fixedDelayMs;
	private int batchSize;
	private long confirmTimeoutMs;

	public boolean isEnabled() {
		return this.enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public long getFixedDelayMs() {
		return this.fixedDelayMs;
	}

	public void setFixedDelayMs(long fixedDelayMs) {
		if (fixedDelayMs <= 0) {
			throw new IllegalArgumentException("app.messaging.outbox.fixed-delay-ms must be positive");
		}
		this.fixedDelayMs = fixedDelayMs;
	}

	public int getBatchSize() {
		return this.batchSize;
	}

	public void setBatchSize(int batchSize) {
		if (batchSize <= 0) {
			throw new IllegalArgumentException("app.messaging.outbox.batch-size must be positive");
		}
		this.batchSize = batchSize;
	}

	public long getConfirmTimeoutMs() {
		return this.confirmTimeoutMs;
	}

	public void setConfirmTimeoutMs(long confirmTimeoutMs) {
		if (confirmTimeoutMs <= 0) {
			throw new IllegalArgumentException("app.messaging.outbox.confirm-timeout-ms must be positive");
		}
		this.confirmTimeoutMs = confirmTimeoutMs;
	}

	@Override
	public String toString() {
		return "OutboxRelayProperties [enabled=" + this.enabled + ", fixedDelayMs=" + this.fixedDelayMs
				+ ", batchSize=" + this.batchSize + ", confirmTimeoutMs=" + this.confirmTimeoutMs + "]";
	}

}
