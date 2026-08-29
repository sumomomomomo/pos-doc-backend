package horse.sumomo.pos_doc_backend.ingestion.archive;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Handle to a bounded temporary file holding one accepted compressed upload.
 *
 * <p>Carries only the temporary {@link Path}, the exact number of bytes
 * written, and the lowercase 64-character SHA-256 digest computed during the
 * copy pass. The record is {@link AutoCloseable}: {@link #close()} deletes
 * only this handle's own temporary file and is idempotent, so it is safe to
 * invoke from try-with-resources on both success and failure paths.
 *
 * <p>{@link #toString()} exposes none of the PII-sensitive values: no path,
 * filename, or digest.
 */
public final class SpooledUpload implements AutoCloseable {

	private final Path tempPath;
	private final long byteCount;
	private final String sha256;
	private boolean closed;

	public SpooledUpload(Path tempPath, long byteCount, String sha256) {
		this.tempPath = Objects.requireNonNull(tempPath, "tempPath must not be null");
		if (byteCount < 0) {
			throw new IllegalArgumentException("byteCount must be >= 0");
		}
		this.byteCount = byteCount;
		this.sha256 = Objects.requireNonNull(sha256, "sha256 must not be null");
		this.closed = false;
	}

	public Path getTempPath() {
		return this.tempPath;
	}

	public long getByteCount() {
		return this.byteCount;
	}

	public String getSha256() {
		return this.sha256;
	}

	/**
	 * Deletes only this handle's temporary file. Idempotent: subsequent
	 * calls and calls after a missing file are no-ops.
	 */
	@Override
	public void close() {
		if (this.closed) {
			return;
		}
		this.closed = true;
		try {
			Files.deleteIfExists(this.tempPath);
		}
		catch (IOException ignored) {
			// The file is already gone; nothing to do.
		}
	}

	@Override
	public String toString() {
		return "SpooledUpload[byteCount=" + this.byteCount + "]";
	}

}
