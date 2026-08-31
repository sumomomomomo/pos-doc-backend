package horse.sumomo.pos_doc_backend.ingestion.api;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed, validated tuning knobs for the ingestion consumer.
 *
 * <p>Bound from {@code app.ingestion.consumer.*}. The listener is only
 * created when {@link #enabled} is true. Concurrency and prefetch are pinned
 * to {@code 1} to match the single-instance deployment; the bounded retry
 * schedule is exactly {@code max-attempts} attempts with capped exponential
 * backoff. The maximum message body size is checked before parsing so a
 * hostile large payload is rejected without ever reaching the JSON parser.
 */
@ConfigurationProperties(prefix = "app.ingestion.consumer")
public final class ConsumerProperties {

	private boolean enabled;
	private int concurrency;
	private int prefetch;
	private int maxAttempts;
	private long initialBackoffMs;
	private double backoffMultiplier;
	private long maxBackoffMs;
	private int maxMessageBytes;

	public ConsumerProperties(boolean enabled, int concurrency, int prefetch, int maxAttempts,
			long initialBackoffMs, double backoffMultiplier, long maxBackoffMs, int maxMessageBytes) {
		if (concurrency <= 0) {
			throw new IllegalArgumentException("app.ingestion.consumer.concurrency must be positive");
		}
		if (prefetch <= 0) {
			throw new IllegalArgumentException("app.ingestion.consumer.prefetch must be positive");
		}
		if (maxAttempts < 1) {
			throw new IllegalArgumentException("app.ingestion.consumer.max-attempts must be >= 1");
		}
		if (initialBackoffMs <= 0) {
			throw new IllegalArgumentException("app.ingestion.consumer.initial-backoff-ms must be positive");
		}
		if (backoffMultiplier < 1.0) {
			throw new IllegalArgumentException("app.ingestion.consumer.backoff-multiplier must be >= 1.0");
		}
		if (maxBackoffMs < initialBackoffMs) {
			throw new IllegalArgumentException(
					"app.ingestion.consumer.max-backoff-ms must be >= initial-backoff-ms");
		}
		if (maxMessageBytes <= 0) {
			throw new IllegalArgumentException("app.ingestion.consumer.max-message-bytes must be positive");
		}
		this.enabled = enabled;
		this.concurrency = concurrency;
		this.prefetch = prefetch;
		this.maxAttempts = maxAttempts;
		this.initialBackoffMs = initialBackoffMs;
		this.backoffMultiplier = backoffMultiplier;
		this.maxBackoffMs = maxBackoffMs;
		this.maxMessageBytes = maxMessageBytes;
	}

	public boolean isEnabled() {
		return this.enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public int getConcurrency() {
		return this.concurrency;
	}

	public void setConcurrency(int concurrency) {
		if (concurrency <= 0) {
			throw new IllegalArgumentException("app.ingestion.consumer.concurrency must be positive");
		}
		this.concurrency = concurrency;
	}

	public int getPrefetch() {
		return this.prefetch;
	}

	public void setPrefetch(int prefetch) {
		if (prefetch <= 0) {
			throw new IllegalArgumentException("app.ingestion.consumer.prefetch must be positive");
		}
		this.prefetch = prefetch;
	}

	public int getMaxAttempts() {
		return this.maxAttempts;
	}

	public void setMaxAttempts(int maxAttempts) {
		if (maxAttempts < 1) {
			throw new IllegalArgumentException("app.ingestion.consumer.max-attempts must be >= 1");
		}
		this.maxAttempts = maxAttempts;
	}

	public long getInitialBackoffMs() {
		return this.initialBackoffMs;
	}

	public void setInitialBackoffMs(long initialBackoffMs) {
		if (initialBackoffMs <= 0) {
			throw new IllegalArgumentException("app.ingestion.consumer.initial-backoff-ms must be positive");
		}
		this.initialBackoffMs = initialBackoffMs;
	}

	public double getBackoffMultiplier() {
		return this.backoffMultiplier;
	}

	public void setBackoffMultiplier(double backoffMultiplier) {
		if (backoffMultiplier < 1.0) {
			throw new IllegalArgumentException("app.ingestion.consumer.backoff-multiplier must be >= 1.0");
		}
		this.backoffMultiplier = backoffMultiplier;
	}

	public long getMaxBackoffMs() {
		return this.maxBackoffMs;
	}

	public void setMaxBackoffMs(long maxBackoffMs) {
		if (maxBackoffMs < this.initialBackoffMs) {
			throw new IllegalArgumentException(
					"app.ingestion.consumer.max-backoff-ms must be >= initial-backoff-ms");
		}
		this.maxBackoffMs = maxBackoffMs;
	}

	public int getMaxMessageBytes() {
		return this.maxMessageBytes;
	}

	public void setMaxMessageBytes(int maxMessageBytes) {
		if (maxMessageBytes <= 0) {
			throw new IllegalArgumentException("app.ingestion.consumer.max-message-bytes must be positive");
		}
		this.maxMessageBytes = maxMessageBytes;
	}

	@Override
	public String toString() {
		return "ConsumerProperties [enabled=" + this.enabled + ", concurrency=" + this.concurrency
				+ ", prefetch=" + this.prefetch + ", maxAttempts=" + this.maxAttempts
				+ ", initialBackoffMs=" + this.initialBackoffMs + ", backoffMultiplier="
				+ this.backoffMultiplier + ", maxBackoffMs=" + this.maxBackoffMs + ", maxMessageBytes="
				+ this.maxMessageBytes + "]";
	}

}