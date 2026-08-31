package horse.sumomo.pos_doc_backend.ingestion.consumer;

/**
 * Categorized consumer failure with a stable, sanitized mapping.
 *
 * <p>The {@link Code} carries the machine-readable code and a fixed,
 * PII-free detail message. Carries no PII: no filenames, no eRef or policy
 * values, no object keys, no hashes, no exception text.
 */
public class ConsumerException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	/**
	 * Stable problem codes used by the consumer. Categories group
	 * nonretryable integrity/archive/message failures (DLQ-bound) versus
	 * transient storage/network/database failures (retryable).
	 */
	public enum Code {

		// Integrity / format / state: nonretryable (DLQ on exhaustion).
		MESSAGE_INVALID(400, "MESSAGE_INVALID",
				"The ingestion message body is malformed or violates the contract."),
		SOURCE_ARCHIVE_MISSING(422, "SOURCE_ARCHIVE_MISSING",
				"The source archive no longer exists in object storage."),
		SOURCE_ARCHIVE_SIZE_MISMATCH(422, "SOURCE_ARCHIVE_SIZE_MISMATCH",
				"The downloaded source archive size does not match its declared size."),
		SOURCE_ARCHIVE_HASH_MISMATCH(422, "SOURCE_ARCHIVE_HASH_MISMATCH",
				"The downloaded source archive hash does not match its declared hash."),
		SOURCE_ARCHIVE_INVALID(422, "SOURCE_ARCHIVE_INVALID",
				"The source archive is not a valid ZIP."),
		RECORD_DELETED(410, "RECORD_DELETED",
				"The POS record was soft-deleted; the message is rejected."),
		ID_MISMATCH(422, "ID_MISMATCH",
				"The message identifiers do not reference the same POS record."),
		EXTRACTION_STATE_CONFLICT(409, "EXTRACTION_STATE_CONFLICT",
				"The previously persisted extraction state is incompatible with this archive."),

		// Transient: retryable.
		SOURCE_STORAGE_UNAVAILABLE(503, "SOURCE_STORAGE_UNAVAILABLE",
				"The source object storage is temporarily unavailable."),
		EXTRACTION_TRANSIENT_FAILURE(503, "EXTRACTION_TRANSIENT_FAILURE",
				"A transient failure occurred during extraction.");

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

		public boolean retryable() {
			return this == SOURCE_STORAGE_UNAVAILABLE || this == EXTRACTION_TRANSIENT_FAILURE;
		}
	}

	private final Code code;

	public ConsumerException(Code code) {
		super(code.detail());
		this.code = code;
	}

	public ConsumerException(Code code, String detail) {
		super(detail != null && !detail.isBlank() ? detail : code.detail());
		this.code = code;
	}

	public ConsumerException(Code code, Throwable cause) {
		super(code.detail(), cause);
		this.code = code;
	}

	public Code getCode() {
		return this.code;
	}

}