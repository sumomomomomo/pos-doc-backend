package horse.sumomo.pos_doc_backend.review;

/**
 * Application-level failure for the persistence-backed POS-record review and
 * search API, with a stable, sanitized mapping to the OpenAPI problem format.
 *
 * <p>The {@link Code} carries the HTTP status, the stable machine-readable
 * problem code, and a fixed, PII-free detail message. Exception messages never
 * contain record identifiers' values, filenames, eRef or policy values, OCR
 * text, object keys, database or driver exception text, or a stack trace.
 */
public class PosRecordApiException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	/**
	 * Stable problem codes with their HTTP status and user-safe detail.
	 */
	public enum Code {
		INVALID_SEARCH_REQUEST(400, "INVALID_SEARCH_REQUEST",
				"The search request is invalid."),
		NO_PATCH_FIELDS(400, "NO_PATCH_FIELDS",
				"The patch must include at least one editable value."),
		POS_RECORD_NOT_FOUND(404, "POS_RECORD_NOT_FOUND",
				"The requested resource does not exist."),
		DUPLICATE_EREF_NUMBER(409, "DUPLICATE_EREF_NUMBER",
				"A POS record with this eRef number already exists."),
		DUPLICATE_POLICY_NUMBER(409, "DUPLICATE_POLICY_NUMBER",
				"A POS record with this policy number already exists."),
		POS_RECORD_NOT_REVIEWABLE(409, "POS_RECORD_NOT_REVIEWABLE",
				"This operation is not allowed in the current record state."),
		POS_RECORD_DELETE_CONFLICT(409, "POS_RECORD_DELETE_CONFLICT",
				"The record changed concurrently; try again."),
		POS_RECORD_VERSION_MISMATCH(412, "POS_RECORD_VERSION_MISMATCH",
				"The expected version does not match the current record version."),
		POS_RECORD_INCOMPLETE(422, "POS_RECORD_INCOMPLETE",
				"The record is not complete enough to verify.");

		private final int httpStatus;
		private final String code;
		private final String detail;

		Code(int httpStatus, String code, String detail) {
			this.httpStatus = httpStatus;
			this.code = code;
			this.detail = detail;
		}

		public int httpStatus() {
			return this.httpStatus;
		}

		public String code() {
			return this.code;
		}

		public String detail() {
			return this.detail;
		}
	}

	private final Code code;

	public PosRecordApiException(Code code) {
		super(code.detail());
		this.code = code;
	}

	public PosRecordApiException(Code code, Throwable cause) {
		super(code.detail(), cause);
		this.code = code;
	}

	public Code getCode() {
		return this.code;
	}

}
