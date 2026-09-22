package horse.sumomo.pos_doc_backend.persistence.model;

/**
 * Durable outcome of one structured field extraction attempt on one document at
 * one prompt version. The enum name is the value stored in the
 * {@code pos_field_extraction.outcome} column.
 *
 * <ul>
 *   <li>{@link #RESOLVED}: a validated canonical value was produced and stored in
 *       {@code value_text}.</li>
 *   <li>{@link #UNKNOWN}: the model answered {@code UNKNOWN} (a valid unresolved
 *       result, not a failure); no value, no error.</li>
 *   <li>{@link #FAILED}: attempts were exhausted or a non-retryable model error
 *       occurred; no value, a stable sanitized {@code error_code}.</li>
 * </ul>
 */
public enum ExtractionOutcome {

	RESOLVED,
	UNKNOWN,
	FAILED

}
