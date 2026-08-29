package horse.sumomo.pos_doc_backend.persistence.model;

/**
 * Status of an ingestion job in the persistence domain.
 *
 * <p>Values match the OpenAPI contract strings exactly. Persistence-domain
 * enum; generated API DTO enums are separate.
 */
public enum JobStatus {

	QUEUED,
	RUNNING,
	RETRY_SCHEDULED,
	COMPLETED,
	FAILED

}
