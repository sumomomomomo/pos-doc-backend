package horse.sumomo.pos_doc_backend.persistence.entity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One unpublished (or already published) domain event in the transactional
 * outbox.
 *
 * <p>The payload is a PII-free JSON document holding generated identifiers
 * only; the {@link #toString()} therefore exposes no payload, aggregate IDs,
 * or error detail. New events start unpublished with zero attempts and an
 * immediately due next-attempt time. {@link #markPublished(Instant)} is
 * idempotent and a published event can never be marked failed.
 * {@link #recordFailure(Instant)} applies a fixed, capped back-off schedule:
 * attempts 1..4 retry after 1s, 5s, 30s, 60s; every attempt after that
 * retries after 300s.
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEventEntity {

	public static final String AGGREGATE_TYPE_INGESTION_JOB = "INGESTION_JOB";
	public static final String EVENT_TYPE_INGESTION_REQUESTED = "INGESTION_REQUESTED";

	private static final long[] BACKOFF_SECONDS = {1L, 5L, 30L, 60L};
	private static final long MAX_BACKOFF_SECONDS = 300L;

	@Id
	@Column(name = "id", nullable = false)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	private UUID id;

	@Column(name = "aggregate_type", nullable = false)
	private String aggregateType;

	@Column(name = "aggregate_id", nullable = false)
	private String aggregateId;

	@Column(name = "event_type", nullable = false)
	private String eventType;

	@Column(name = "payload_json", nullable = false)
	private String payloadJson;

	@Column(name = "created_at_epoch_ms", nullable = false)
	private Instant createdAt;

	@Column(name = "published_at_epoch_ms")
	private Instant publishedAt;

	@Column(name = "attempt_count", nullable = false)
	private long attemptCount;

	@Column(name = "next_attempt_at_epoch_ms", nullable = false)
	private Instant nextAttemptAt;

	protected OutboxEventEntity() {
		// JPA only
	}

	public OutboxEventEntity(UUID id, UUID aggregateId, String payloadJson, Instant createdAt) {
		if (id == null) {
			throw new IllegalArgumentException("id must not be null");
		}
		if (aggregateId == null) {
			throw new IllegalArgumentException("aggregateId must not be null");
		}
		if (payloadJson == null || payloadJson.isBlank()) {
			throw new IllegalArgumentException("payloadJson must not be blank");
		}
		if (createdAt == null) {
			throw new IllegalArgumentException("createdAt must not be null");
		}
		this.id = id;
		this.aggregateType = AGGREGATE_TYPE_INGESTION_JOB;
		this.aggregateId = aggregateId.toString();
		this.eventType = EVENT_TYPE_INGESTION_REQUESTED;
		this.payloadJson = payloadJson;
		this.createdAt = createdAt;
		this.publishedAt = null;
		this.attemptCount = 0;
		this.nextAttemptAt = createdAt;
	}

	/**
	 * Marks this event published. Idempotent: calling it again (or after a
	 * previous failure) keeps the first publication instant. A published
	 * event can never be marked failed afterwards.
	 */
	public void markPublished(Instant at) {
		Objects.requireNonNull(at, "publication instant must not be null");
		if (this.publishedAt == null) {
			this.publishedAt = at;
		}
	}

	/**
	 * Records one failed publication attempt and schedules the next attempt
	 * using the capped back-off schedule. Rejects an already published
	 * event.
	 */
	public void recordFailure(Instant now) {
		Objects.requireNonNull(now, "failure instant must not be null");
		if (this.publishedAt != null) {
			throw new IllegalStateException("a published outbox event cannot be marked failed");
		}
		this.attemptCount = this.attemptCount + 1;
		long backoffSeconds = this.attemptCount - 1 < BACKOFF_SECONDS.length
				? BACKOFF_SECONDS[(int) (this.attemptCount - 1)]
				: MAX_BACKOFF_SECONDS;
		this.nextAttemptAt = now.plusSeconds(backoffSeconds);
	}

	public UUID getId() {
		return this.id;
	}

	public String getAggregateType() {
		return this.aggregateType;
	}

	public String getAggregateId() {
		return this.aggregateId;
	}

	public String getEventType() {
		return this.eventType;
	}

	public String getPayloadJson() {
		return this.payloadJson;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

	public Instant getPublishedAt() {
		return this.publishedAt;
	}

	public long getAttemptCount() {
		return this.attemptCount;
	}

	public Instant getNextAttemptAt() {
		return this.nextAttemptAt;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof OutboxEventEntity that)) {
			return false;
		}
		return this.id != null && this.id.equals(that.id);
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(this.id);
	}

	@Override
	public String toString() {
		return "OutboxEventEntity[id=" + this.id + ", attemptCount=" + this.attemptCount
				+ ", publishedAt=" + (this.publishedAt == null ? "null" : "set") + "]";
	}

}
