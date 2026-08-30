package horse.sumomo.pos_doc_backend.ingestion.messaging;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import horse.sumomo.pos_doc_backend.persistence.entity.OutboxEventEntity;
import horse.sumomo.pos_doc_backend.persistence.repository.OutboxEventRepository;

/**
 * Short, individually transactional state transitions for outbox events.
 *
 * <p>Each public method opens its own transaction and reloads the row
 * before mutating it, so a relay run never holds a SQLite transaction open
 * while waiting for the broker. The mutations are guarded by
 * {@code publishedAt IS NULL} semantics on the reloaded entity: a row that
 * was already published by a concurrent or earlier run is never marked
 * failed, and a published row is never double-stamped.
 */
@Service
public class OutboxEventStateService {

	private final OutboxEventRepository repository;

	public OutboxEventStateService(OutboxEventRepository repository) {
		this.repository = repository;
	}

	/**
	 * A detached snapshot of one due event: the event identifier, the
	 * aggregate (job) identifier used as the AMQP correlation ID, and the
	 * stored payload bytes. No live JPA entity is kept across the network
	 * call.
	 */
	public record OutboxSnapshot(UUID id, UUID jobId, byte[] payload) {
	}

	/**
	 * Loads a bounded batch of due, still-unpublished events, oldest first.
	 */
	@Transactional(readOnly = true)
	public List<OutboxSnapshot> dueSnapshots(Instant now, int limit) {
		// The batch limit is enforced at the database level via the Pageable
		// argument (LIMIT on the JPQL query), so at most `limit` due rows are
		// ever loaded into memory.
		Pageable page = PageRequest.of(0, limit);
		return this.repository.findPendingDue(now, page).stream()
				.map(e -> new OutboxSnapshot(e.getId(), UUID.fromString(e.getAggregateId()),
						e.getPayloadJson().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
				.toList();
	}

	/**
	 * Reloads the row and marks it published, only if it is still
	 * unpublished.
	 */
	@Transactional
	public void markPublished(UUID id, Instant at) {
		this.repository.findById(id).ifPresent(event -> {
			if (event.getPublishedAt() == null) {
				event.markPublished(at);
			}
		});
	}

	/**
	 * Reloads the row, increments the attempt count, and schedules the next
	 * attempt, only if it is still unpublished.
	 */
	@Transactional
	public void recordFailure(UUID id, Instant now) {
		this.repository.findById(id).ifPresent(event -> {
			if (event.getPublishedAt() == null) {
				event.recordFailure(now);
			}
		});
	}

}
