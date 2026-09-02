package horse.sumomo.pos_doc_backend.infrastructure.minio;

/**
 * Unchecked wrapper for failures of the object storage layer (MinIO errors,
 * I/O problems, timeouts, authentication failures).
 *
 * <p>Messages may include the object key but must never include credentials
 * or document contents.
 *
 * <p>A missing object is reported as a {@link MissingObjectException}, a
 * subclass of this type, so callers can distinguish a typed
 * {@code NoSuchKey} / {@code NoSuchObject} condition from any other storage
 * failure without inspecting exception-message text.
 */
public class ObjectStorageException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public ObjectStorageException(String message, Throwable cause) {
		super(message, cause);
	}

	public ObjectStorageException(String message) {
		super(message);
	}

	/**
	 * Indicates that the requested object does not exist in the bucket.
	 *
	 * <p>Thrown only for MinIO {@code NoSuchKey} / {@code NoSuchObject}
	 * responses. Every other storage failure (missing bucket, connection
	 * failure, timeout, authentication failure) is reported as a plain
	 * {@link ObjectStorageException}.
	 */
	public static final class MissingObjectException extends ObjectStorageException {

		private static final long serialVersionUID = 1L;

		public MissingObjectException(String message, Throwable cause) {
			super(message, cause);
		}

	}

}
