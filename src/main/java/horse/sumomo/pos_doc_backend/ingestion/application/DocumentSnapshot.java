package horse.sumomo.pos_doc_backend.ingestion.application;

import java.util.UUID;

/**
 * A minimal, PII-light view of one PDF document used purely for candidate
 * selection: its id, its ZIP entry/sequence order, and its stored filename (the
 * ZIP entry path). It intentionally carries no PII and no image bytes.
 *
 * @param documentId     the document id
 * @param sequenceNumber the ZIP entry/sequence order (0-based, ascending)
 * @param filename       the stored original filename (ZIP entry path); may be null
 */
public record DocumentSnapshot(UUID documentId, int sequenceNumber, String filename) {

	public DocumentSnapshot {
		if (documentId == null) {
			throw new IllegalArgumentException("documentId must not be null");
		}
		if (sequenceNumber < 0) {
			throw new IllegalArgumentException("sequenceNumber must be >= 0");
		}
	}

}
