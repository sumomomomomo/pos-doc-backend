package horse.sumomo.pos_doc_backend.content;

/**
 * Application-level failure for the protected document/source-archive content
 * endpoints, with a stable, sanitized mapping to the OpenAPI problem format.
 *
 * <p>Problem codes are fixed and PII-free. Exception messages never carry the
 * bucket, object key, filename, endpoint, MinIO response body, or the underlying
 * exception message.
 */
public class DocumentContentException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public enum Code {
		POS_RECORD_NOT_FOUND(404, "POS_RECORD_NOT_FOUND", "The requested resource does not exist."),
		DOCUMENT_NOT_FOUND(404, "DOCUMENT_NOT_FOUND", "The requested resource does not exist."),
		DOCUMENT_STORAGE_UNAVAILABLE(503, "DOCUMENT_STORAGE_UNAVAILABLE",
				"Document storage is temporarily unavailable."),
		DOCUMENT_CONTENT_UNAVAILABLE(500, "DOCUMENT_CONTENT_UNAVAILABLE", "The document content is unavailable.");

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

	public DocumentContentException(Code code) {
		super(code.detail());
		this.code = code;
	}

	public DocumentContentException(Code code, Throwable cause) {
		super(code.detail(), cause);
		this.code = code;
	}

	public Code getCode() {
		return this.code;
	}

}
