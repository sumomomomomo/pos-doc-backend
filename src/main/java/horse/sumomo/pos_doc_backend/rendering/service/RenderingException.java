package horse.sumomo.pos_doc_backend.rendering.service;

/**
 * Categorized rendering failure with a stable, sanitized mapping.
 *
 * <p>The {@link Code} carries the machine-readable code and a fixed,
 * PII-free detail message. Carries no PII: no filenames, no object keys, no
 * hashes, no temp paths, no raw external exception text.
 */
public class RenderingException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	/**
	 * Stable problem codes used by the rendering pipeline.
	 */
	public enum Code {

		DOCUMENT_NOT_FOUND("DOCUMENT_NOT_FOUND", false,
				"The document does not exist."),
		DOCUMENT_DELETED("DOCUMENT_DELETED", false,
				"The parent POS record has been soft-deleted."),
		PDF_METADATA_INVALID("PDF_METADATA_INVALID", false,
				"The persisted PDF metadata is invalid."),
		PDF_OBJECT_MISSING("PDF_OBJECT_MISSING", false,
				"The declared PDF object does not exist in object storage."),
		PDF_SIZE_MISMATCH("PDF_SIZE_MISMATCH", false,
				"The actual PDF byte size differs from the persisted size."),
		PDF_HASH_MISMATCH("PDF_HASH_MISMATCH", false,
				"The actual PDF digest differs from the persisted digest."),
		PDF_STORAGE_UNAVAILABLE("PDF_STORAGE_UNAVAILABLE", true,
				"The PDF object storage is temporarily unavailable."),
		PDF_INVALID("PDF_INVALID", false,
				"The PDF is malformed, empty, or encrypted."),
		PAGE_DIMENSIONS_INVALID("PAGE_DIMENSIONS_INVALID", false,
				"The first page has invalid or unsupported geometry."),
		RENDER_LIMIT_EXCEEDED("RENDER_LIMIT_EXCEEDED", false,
				"A rendering pixel or byte limit was exceeded."),
		RENDER_FAILED("RENDER_FAILED", false,
				"The PDF could not be rendered."),
		RENDER_INTERRUPTED("RENDER_INTERRUPTED", true,
				"Rendering was interrupted while waiting for the render permit."),
		TEMP_STORAGE_UNAVAILABLE("TEMP_STORAGE_UNAVAILABLE", true,
				"Local temporary storage is unavailable.");

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

	public RenderingException(Code code) {
		super(code.detail());
		this.code = code;
	}

	public RenderingException(Code code, Throwable cause) {
		super(code.detail(), cause);
		this.code = code;
	}

	public Code getCode() {
		return this.code;
	}

}
