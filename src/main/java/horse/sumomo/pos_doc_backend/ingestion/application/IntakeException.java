package horse.sumomo.pos_doc_backend.ingestion.application;

/**
 * Application-level intake failure with a stable, sanitized mapping to the
 * OpenAPI problem format.
 *
 * <p>The {@link Code} carries the HTTP status, the stable machine-readable
 * problem code, and a fixed, PII-free detail message. Exception messages
 * never contain filenames, eRef or policy values, object keys, hashes, or
 * raw storage/database/broker text.
 */
public class IntakeException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	/**
	 * Stable problem codes with their HTTP status and user-safe detail.
	 */
	public enum Code {
		MISSING_FILE(400, "MISSING_FILE", "The multipart file part is missing."),
		INVALID_POLICY_NUMBER(400, "INVALID_POLICY_NUMBER", "The supplied policy number is invalid."),
		INVALID_ARCHIVE_FILENAME(400, "INVALID_ARCHIVE_FILENAME", "The archive filename is invalid."),
		ARCHIVE_TOO_LARGE(413, "ARCHIVE_TOO_LARGE", "The archive exceeds the configured size limit."),
		UNSUPPORTED_ARCHIVE_TYPE(415, "UNSUPPORTED_ARCHIVE_TYPE", "The uploaded file is not a supported ZIP archive."),
		INVALID_ARCHIVE(422, "INVALID_ARCHIVE", "The archive is syntactically valid but cannot be processed."),
		DUPLICATE_EREF_NUMBER(409, "DUPLICATE_EREF_NUMBER",
				"A POS record with this eRef number already exists."),
		DUPLICATE_POLICY_NUMBER(409, "DUPLICATE_POLICY_NUMBER",
				"A POS record with this policy number already exists."),
		INGESTION_JOB_NOT_FOUND(404, "INGESTION_JOB_NOT_FOUND", "The requested resource does not exist."),
		INGESTION_INTAKE_FAILED(500, "INGESTION_INTAKE_FAILED", "Unexpected internal error.");

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

	public IntakeException(Code code) {
		super(code.detail());
		this.code = code;
	}

	public IntakeException(Code code, Throwable cause) {
		super(code.detail(), cause);
		this.code = code;
	}

	public Code getCode() {
		return this.code;
	}

}
