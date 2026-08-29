package horse.sumomo.pos_doc_backend.ingestion.messaging;

import java.util.UUID;

/**
 * Publishes one stored outbox payload to RabbitMQ and reports whether the
 * broker durably accepted and routed it.
 *
 * <p>A {@code true} result requires both a positive publisher confirm and
 * the absence of a mandatory return. A {@code false} result (or a thrown
 * exception) means the caller must keep the event unpublished and retry
 * later; it must never be treated as proof of publication.
 */
public interface OutboxPublisher {

	/**
	 * Publishes the exact stored JSON bytes.
	 *
	 * @param payloadJson the exact bytes stored in the outbox row
	 * @param eventId     outbox event ID (AMQP message ID)
	 * @param jobId       ingestion job ID (AMQP correlation ID)
	 * @return {@code true} only after a positive publisher confirm without a
	 *         mandatory return
	 * @throws Exception when the broker is unreachable, the confirm times
	 *             out, or the publish fails for any reason
	 */
	boolean publish(byte[] payloadJson, UUID eventId, UUID jobId) throws Exception;

}
