package horse.sumomo.pos_doc_backend.persistence.model;

/**
 * Processing status of a single POS document.
 *
 * <p>Values match the OpenAPI contract strings exactly. Persistence-domain
 * enum; generated API DTO enums are separate.
 */
public enum DocumentProcessingStatus {

	PENDING,
	PROCESSING,
	COMPLETED,
	FAILED,
	SKIPPED

}
