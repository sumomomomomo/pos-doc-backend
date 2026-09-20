package horse.sumomo.pos_doc_backend.ocr.application;

/**
 * Result of parsing one structured field answer.
 *
 * <ul>
 *   <li>{@link ParseKind#RESOLVED}: the raw model text normalized and validated
 *       to a canonical {@link #value} (a validated name, or an ISO
 *       {@code yyyy-MM-dd} date).</li>
 *   <li>{@link ParseKind#UNKNOWN}: the model answered the literal unresolved
 *       token ({@code UNKNOWN}/{@code NONE}/{@code N/A}); a valid unresolved
 *       result, not an error. {@code value} is {@code null}.</li>
 *   <li>{@link ParseKind#INVALID}: the raw text did not match the field's
 *       expected form (or was blank); {@code value} is {@code null}. The
 *       workflow treats this as a failed attempt (retryable).</li>
 * </ul>
 *
 * <p>This is a value type with no PII beyond the already-validated canonical
 * value.
 */
public record FieldAnswerParse(ParseKind kind, String value) {

	/** The three parse outcomes. */
	public enum ParseKind {
		RESOLVED,
		UNKNOWN,
		INVALID
	}

	public static FieldAnswerParse resolved(String value) {
		return new FieldAnswerParse(ParseKind.RESOLVED, value);
	}

	public static FieldAnswerParse unknown() {
		return new FieldAnswerParse(ParseKind.UNKNOWN, null);
	}

	public static FieldAnswerParse invalid() {
		return new FieldAnswerParse(ParseKind.INVALID, null);
	}

}
