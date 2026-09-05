package horse.sumomo.pos_doc_backend.ocr.service;

/**
 * Categorized OCR failure with a stable, sanitized mapping.
 *
 * <p>The {@link Code} carries the machine-readable code and a fixed,
 * PII-free detail message. Carries no PII: no filenames, no object keys,
 * no OCR text, no raw response body, no temp paths, no raw external
 * exception text.
 */
public class OcrException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	/**
	 * Stable problem codes used by the OCR pipeline.
	 */
	public enum Code {

		OCR_IMAGE_INVALID("OCR_IMAGE_INVALID", false,
				"The PNG metadata, signature, or size is invalid."),
		OCR_IMAGE_UNAVAILABLE("OCR_IMAGE_UNAVAILABLE", true,
				"The temporary PNG cannot be opened or read."),
		OCR_INTERRUPTED("OCR_INTERRUPTED", true,
				"OCR was interrupted while waiting or executing."),
		OCR_TIMEOUT("OCR_TIMEOUT", true,
				"An OCR connect, read, or call timeout occurred."),
		OCR_SERVICE_BUSY("OCR_SERVICE_BUSY", true,
				"The OCR service is busy (HTTP 408 or 429)."),
		OCR_SERVICE_UNAVAILABLE("OCR_SERVICE_UNAVAILABLE", true,
				"The OCR service is unavailable (connection failure or HTTP 5xx)."),
		OCR_AUTH_FAILED("OCR_AUTH_FAILED", false,
				"The OCR service rejected the request (HTTP 401 or 403)."),
		OCR_REQUEST_REJECTED("OCR_REQUEST_REJECTED", false,
				"The OCR service rejected the request (nonretryable 4xx)."),
		OCR_PROTOCOL_ERROR("OCR_PROTOCOL_ERROR", false,
				"An OCR protocol error occurred (redirect, unexpected status, or invalid content type)."),
		OCR_RESPONSE_TOO_LARGE("OCR_RESPONSE_TOO_LARGE", true,
				"The OCR response exceeds the configured byte limit."),
		OCR_RESPONSE_INVALID("OCR_RESPONSE_INVALID", false,
				"The OCR response is malformed or structurally invalid."),
		OCR_OUTPUT_EMPTY("OCR_OUTPUT_EMPTY", false,
				"The OCR response contains no text."),
		OCR_OUTPUT_TRUNCATED("OCR_OUTPUT_TRUNCATED", false,
				"The OCR output was truncated because the token limit was reached.");

		private final String code;
		private final boolean retryable;
		private final String detail;

		Code(String code, boolean retryable, String detail) {
			this.code = code;
			this.retryable = retryable;
			this.detail = detail;
		}

		public String code() {
			return this.code;
		}

		public boolean retryable() {
			return this.retryable;
		}

		public String detail() {
			return this.detail;
		}
	}

	private final Code code;

	public OcrException(Code code) {
		super(code.detail());
		this.code = code;
	}

	public OcrException(Code code, Throwable cause) {
		super(code.detail(), cause);
		this.code = code;
	}

	public Code getCode() {
		return this.code;
	}

}
