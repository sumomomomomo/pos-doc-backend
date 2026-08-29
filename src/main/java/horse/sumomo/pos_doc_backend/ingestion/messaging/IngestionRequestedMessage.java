package horse.sumomo.pos_doc_backend.ingestion.messaging;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The only message payload published to RabbitMQ in this task.
 *
 * <p>Carries generated identifiers only. It must never contain an eRef
 * number, policy number, policyholder or consultant name, filename, MinIO
 * bucket or object key, SHA-256, error text, or document content.
 *
 * <p>Serialized exactly once when the outbox row is created; the stored
 * JSON is republished verbatim on every retry.
 *
 * @param eventId       the outbox event UUID (also the AMQP message ID)
 * @param jobId         the ingestion job UUID (also the AMQP correlation ID)
 * @param posRecordId   the POS record UUID
 * @param schemaVersion always {@link #SCHEMA_VERSION} in this task
 * @param occurredAt    the request timestamp, serialized as a UTC
 *            ISO-8601 instant by the application's configured Jackson
 *            mapper
 */
public record IngestionRequestedMessage(UUID eventId, UUID jobId, UUID posRecordId, int schemaVersion,
		Instant occurredAt) {

	public static final int SCHEMA_VERSION = 1;

	public IngestionRequestedMessage {
		Objects.requireNonNull(eventId, "eventId must not be null");
		Objects.requireNonNull(jobId, "jobId must not be null");
		Objects.requireNonNull(posRecordId, "posRecordId must not be null");
		Objects.requireNonNull(occurredAt, "occurredAt must not be null");
	}

	/**
	 * Builds a current-schema message.
	 */
	public static IngestionRequestedMessage of(UUID eventId, UUID jobId, UUID posRecordId, Instant occurredAt) {
		return new IngestionRequestedMessage(eventId, jobId, posRecordId, SCHEMA_VERSION, occurredAt);
	}

	@Override
	public String toString() {
		return "IngestionRequestedMessage[eventId=" + this.eventId + ", jobId=" + this.jobId + ", posRecordId="
				+ this.posRecordId + ", schemaVersion=" + this.schemaVersion + ", occurredAt=" + this.occurredAt
				+ "]";
	}

}
