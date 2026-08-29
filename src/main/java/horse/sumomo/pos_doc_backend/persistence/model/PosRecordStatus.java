package horse.sumomo.pos_doc_backend.persistence.model;

/**
 * Lifecycle status of a POS record in the persistence domain.
 *
 * <p>Values match the OpenAPI contract strings exactly. This is a
 * persistence-domain enum: JPA entities use it directly, while the generated
 * API DTO enums remain separate and are never imported here.
 */
public enum PosRecordStatus {

	UPLOADED,
	VALIDATING,
	PROCESSING,
	REVIEW_REQUIRED,
	COMPLETED,
	FAILED

}
