package horse.sumomo.pos_doc_backend.ingestion.consumer;

import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import horse.sumomo.pos_doc_backend.ingestion.api.ConsumerProperties;
import horse.sumomo.pos_doc_backend.ingestion.messaging.IngestionRequestedMessage;

/**
 * Consumes the {@code pos.ingestion.jobs} queue.
 *
 * <p>Steps in order:
 * <ol>
 *   <li>Validate the raw AMQP envelope and the parsed JSON body.</li>
 *   <li>Hand the message identifiers to {@link IngestionConsumerService},
 *       which claims the job, downloads the source ZIP, runs the existing
 *       ZIP validator and the streaming PDF extractor, uploads each PDF,
 *       and persists the document rows atomically.</li>
 *   <li>Bounded retry: retry transient failures up to
 *       {@code max-attempts} times with capped exponential backoff within
 *       the same delivery; nonretryable failures and exhausted retries
 *       reject the message so it lands on the DLQ.</li>
 * </ol>
 *
 * <p>Listener concurrency is pinned to 1 by
 * {@link ConsumerConfiguration#ingestionListenerContainerFactory}; prefetch
 * is pinned to 1.
 */
@Component
@ConditionalOnProperty(prefix = "app.ingestion.consumer", name = "enabled", havingValue = "true")
public class IngestionListener {

	private static final Logger log = LoggerFactory.getLogger(IngestionListener.class);

	private final IngestionMessageValidator validator;
	private final IngestionConsumerService consumerService;
	private final ConsumerProperties consumerProperties;

	public IngestionListener(IngestionMessageValidator validator, IngestionConsumerService consumerService,
			ConsumerProperties consumerProperties) {
		this.validator = validator;
		this.consumerService = consumerService;
		this.consumerProperties = consumerProperties;
	}

	@RabbitListener(queues = "#{@ingestionQueueNameProvider.name}", containerFactory = "ingestionListenerContainerFactory")
	public void onMessage(Message message) {
		IngestionRequestedMessage parsed;
		try {
			parsed = this.validator.validate(message);
		}
		catch (ConsumerException e) {
			log.warn("Malformed message reached the consumer (category=message-invalid); detail={}; rejecting to DLQ",
					e.getMessage());
			throw e;
		}

		IngestionConsumerService.IngestionMessageIdentifiers ids =
				new IngestionConsumerService.IngestionMessageIdentifiers(parsed.jobId(), parsed.posRecordId());
		int maxAttempts = this.consumerProperties.getMaxAttempts();
		ConsumerException last = null;
		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			try {
				this.consumerService.consume(ids);
				return;
			}
			catch (ConsumerException e) {
				last = e;
				if (!e.getCode().retryable()) {
					log.warn("Nonretryable failure (category={}); rejecting to DLQ", e.getCode().code());
					throw e;
				}
				if (attempt >= maxAttempts) {
					log.warn("Retry budget exhausted (category={}); rejecting to DLQ", e.getCode().code());
					throw e;
				}
				long backoff = computeBackoffMs(attempt);
				log.debug("Transient failure (category={}); attempt {}/{}; sleeping {}ms", e.getCode().code(),
						attempt, maxAttempts, backoff);
				try {
					Thread.sleep(backoff);
				}
				catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					throw e;
				}
			}
		}
		if (last != null) {
			throw last;
		}
	}

	private long computeBackoffMs(int attempt) {
		double multiplier = this.consumerProperties.getBackoffMultiplier();
		long initial = this.consumerProperties.getInitialBackoffMs();
		long max = this.consumerProperties.getMaxBackoffMs();
		double computed = initial * Math.pow(multiplier, attempt - 1);
		long bounded = (long) Math.min(computed, max);
		// Add a tiny jitter (up to 25% of initial) to avoid synchronized retries
		// in the unlikely event of multiple listeners running simultaneously.
		long jitter = ThreadLocalRandom.current().nextLong(initial / 4 + 1);
		return Math.min(bounded + jitter, max);
	}

}