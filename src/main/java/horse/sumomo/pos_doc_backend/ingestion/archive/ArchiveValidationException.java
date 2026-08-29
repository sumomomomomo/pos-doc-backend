package horse.sumomo.pos_doc_backend.ingestion.archive;

/**
 * Raised when an uploaded archive or its declared metadata is rejected by
 * intake validation.
 *
 * <p>Exception messages are sanitized by design: they carry a stable reason
 * category only and never echo the submitted filename, entry name, eRef,
 * policy number, object key, hash, or byte contents. The category is what
 * the error mapper turns into an HTTP problem response.
 */
public class ArchiveValidationException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	/**
	 * Stable, locale-independent reason categories.
	 */
	public enum Category {
		/** Missing multipart file part. */
		EMPTY_UPLOAD,
		/** Compressed byte count exceeded the configured limit. */
		ARCHIVE_TOO_LARGE,
		/** Declared content type outside the allowlist, or bytes are not a ZIP. */
		UNSUPPORTED_ARCHIVE_TYPE,
		/** Corrupt/encrypted ZIP, unsafe path, non-PDF entry, invalid PDF
		 * signature, entry-count or decompression limit or ratio violation. */
		INVALID_ARCHIVE,
		/** Missing or unusable original filename, or eRef derivation failed. */
		INVALID_ARCHIVE_FILENAME
	}

	private final Category category;

	public ArchiveValidationException(Category category, String message) {
		super(message);
		this.category = category;
	}

	public ArchiveValidationException(Category category, String message, Throwable cause) {
		super(message, cause);
		this.category = category;
	}

	public Category getCategory() {
		return this.category;
	}

}
