package horse.sumomo.pos_doc_backend.ingestion.messaging;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import horse.sumomo.pos_doc_backend.ingestion.api.OutboxRelayProperties;

/**
 * Scheduled relay that publishes due outbox events to RabbitMQ.
 *
 * <p>Runs at the configured fixed delay only when
 * {@code app.messaging.outbox.enabled=true}. An {@link AtomicBoolean} guard
 * prevents overlapping invocations within this JVM (the deployment is a
 * single instance, so no distributed lock is used). At most the configured
 * batch size of events is processed per run.
 *
 * <p>For each due event the relay: loads a detached snapshot, publishes the
 * stored payload <em>outside</em> any database transaction, waits for a
 * positive publisher confirm (an unroutable mandatory return is a failure),
 * and then applies the result through a separate transactional state
 * method that reloads the row. RabbitMQ being unavailable therefore never
 * rolls back an accepted upload, never marks the ingestion job failed, and
 * never retries in a tight loop: the row simply stays unpublished with a
 * bounded back-off.
 *
 * <p>Publication is at-least-once: a crash after the broker confirms but
 * before the row is stamped published can cause the same event to be
 * published again. The future consumer must be idempotent on
 * {@code eventId}/{@code jobId}.
 */
@Component
@ConditionalOnProperty(prefix = "app.messaging.outbox", name = "enabled", havingValue = "true")
public class OutboxRelay {

	private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

	private final OutboxEventStateService stateService;
	private final OutboxPublisher publisher;
	private final OutboxRelayProperties properties;
	private final AtomicBoolean running = new AtomicBoolean(false);

	public OutboxRelay(OutboxEventStateService stateService, OutboxPublisher publisher,
			OutboxRelayProperties properties) {
		this.stateService = stateService;
		this.publisher = publisher;
		this.properties = properties;
	}

	/**
	 * Scheduled entry point. Safe to call directly (tests invoke one cycle
	 * without sleeping for the scheduler).
	 */
	@Scheduled(fixedDelayString = "#{${app.messaging.outbox.fixed-delay-ms}}")
	public void relayOnce() {
		if (!this.running.compareAndSet(false, true)) {
			log.debug("Outbox relay run skipped: previous run still in progress");
			return;
		}
		try {
			runOnce(Instant.now());
		}
		finally {
			this.running.set(false);
		}
	}

	/**
	 * Executes one bounded relay cycle. Package-visible so tests can drive
	 * it directly with a deterministic clock.
	 */
	void runOnce(Instant now) {
		List<OutboxEventStateService.OutboxSnapshot> batch;
		try {
			batch = this.stateService.dueSnapshots(now, this.properties.getBatchSize());
		}
		catch (RuntimeException e) {
			log.debug("Outbox relay could not load the pending batch (category=db-read-failure)");
			return;
		}

		for (OutboxEventStateService.OutboxSnapshot snapshot : batch) {
			publishOne(snapshot, now);
		}
	}

	private void publishOne(OutboxEventStateService.OutboxSnapshot snapshot, Instant now) {
		UUID eventId = snapshot.id();
		boolean accepted;
		try {
			accepted = this.publisher.publish(snapshot.payload(), eventId, snapshot.jobId());
		}
		catch (Exception e) {
			// The publisher reports broker failure as false; an unexpected
			// exception is treated the same way. No raw broker text is
			// logged.
			log.debug("Outbox publish raised an unexpected failure (category=unexpected); eventId={}",
					eventId);
			accepted = false;
		}

		try {
			if (accepted) {
				this.stateService.markPublished(eventId, Instant.now());
			}
			else {
				this.stateService.recordFailure(eventId, Instant.now());
			}
		}
		catch (RuntimeException e) {
			log.debug("Outbox state update failed (category=db-write-failure); eventId={}", eventId);
		}
	}

}
