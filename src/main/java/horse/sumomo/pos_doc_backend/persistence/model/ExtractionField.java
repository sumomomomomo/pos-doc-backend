package horse.sumomo.pos_doc_backend.persistence.model;

/**
 * The three structured {@code pos_record} fields extracted from a document's
 * first page. The enum name is the value stored in the
 * {@code pos_field_extraction.field_name} column.
 *
 * <p>There is deliberately no fourth field in this workflow, and the policy
 * number is not one of them (it is supplied through the upload/update APIs).
 */
public enum ExtractionField {

	POLICYHOLDER_NAME,
	CONSULTANT_NAME,
	POLICY_CREATE_DATE

}
