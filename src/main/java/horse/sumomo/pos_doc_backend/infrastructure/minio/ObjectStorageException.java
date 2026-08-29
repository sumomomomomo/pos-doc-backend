package horse.sumomo.pos_doc_backend.infrastructure.minio;

/**
 * Unchecked wrapper for failures of the object storage layer (MinIO errors,
 * I/O problems, timeouts, authentication failures).
 *
 * <p>Messages may include the object key but must never include credentials
 * or document contents.
 */
public class ObjectStorageException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public ObjectStorageException(String message, Throwable cause) {
		super(message, cause);
	}

	public ObjectStorageException(String message) {
		super(message);
	}

}
