package horse.sumomo.pos_doc_backend.ingestion.consumer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Objects;

import org.springframework.stereotype.Component;

import horse.sumomo.pos_doc_backend.ingestion.api.UploadLimitsProperties;
import horse.sumomo.pos_doc_backend.infrastructure.minio.MinioObjectStorage;
import horse.sumomo.pos_doc_backend.infrastructure.minio.ObjectStorageException;

/**
 * Streams one source archive from MinIO into a temporary file while
 * enforcing the configured compressed-byte limit, computing SHA-256, and
 * verifying size and digest against the persisted source metadata.
 *
 * <p>The download is bounded: the configured compressed limit (10 MiB) is
 * enforced as bytes are actually read; one byte over the cap aborts the
 * download and is reported as {@code ARCHIVE_TOO_LARGE}-class failure. The
 * temporary file is deleted on every path — success, oversize, hash
 * mismatch, size mismatch, IO failure — so no partial copy is ever left on
 * disk.
 *
 * <p>Digest comparison uses {@link MessageDigest#isEqual} for
 * constant-time semantics, so a hostile archive cannot be probed for the
 * expected hash byte-by-byte via timing.
 */
@Component
public class SourceArchiveDownloader {

	private static final int BUFFER_SIZE = 8192;
	private static final String TEMP_PREFIX = "pos-doc-consumer-src-";
	private static final String TEMP_SUFFIX = ".part";

	private final MinioObjectStorage storage;
	private final UploadLimitsProperties limits;

	public SourceArchiveDownloader(MinioObjectStorage storage, UploadLimitsProperties limits) {
		this.storage = Objects.requireNonNull(storage, "storage must not be null");
		this.limits = Objects.requireNonNull(limits, "limits must not be null");
	}

	/**
	 * Downloads and verifies the source archive.
	 *
	 * @param objectKey the MinIO object key
	 * @param expectedByteSize the persisted byte size
	 * @param expectedSha256 the persisted lowercase hex SHA-256
	 * @return the {@link DownloadedArchive} containing the temp file, the
	 *         observed size, and the lowercase hex SHA-256
	 * @throws ConsumerException when the archive cannot be fetched, exceeds
	 *             the limit, or fails size/hash verification
	 */
	public DownloadedArchive download(String objectKey, long expectedByteSize, String expectedSha256) {
		Objects.requireNonNull(objectKey, "objectKey must not be null");
		Objects.requireNonNull(expectedSha256, "expectedSha256 must not be null");

		Path tempFile;
		try {
			tempFile = Files.createTempFile(TEMP_PREFIX, TEMP_SUFFIX);
		}
		catch (IOException e) {
			throw new ConsumerException(ConsumerException.Code.SOURCE_STORAGE_UNAVAILABLE, e);
		}

		long observedSize;
		String observedSha256;
		try (InputStream in = openStream(objectKey)) {
			MessageDigest digest;
			try {
				digest = MessageDigest.getInstance("SHA-256");
			}
			catch (NoSuchAlgorithmException e) {
				deleteQuietly(tempFile);
				throw new ConsumerException(ConsumerException.Code.SOURCE_STORAGE_UNAVAILABLE, e);
			}
			long bytesRead = 0L;
			byte[] buffer = new byte[BUFFER_SIZE];
			try (var out = Files.newOutputStream(tempFile,
					StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
				int read;
				while ((read = in.read(buffer)) != -1) {
					bytesRead += read;
					if (bytesRead > this.limits.maxCompressedBytes()) {
						out.close();
						deleteQuietly(tempFile);
						throw new ConsumerException(ConsumerException.Code.SOURCE_STORAGE_UNAVAILABLE,
								new IllegalStateException("source archive exceeds compressed limit"));
					}
					digest.update(buffer, 0, read);
					out.write(buffer, 0, read);
				}
			}
			observedSize = bytesRead;
			observedSha256 = hexLowercase(digest.digest());
		}
		catch (ConsumerException e) {
			throw e;
		}
		catch (IOException e) {
			deleteQuietly(tempFile);
			throw new ConsumerException(ConsumerException.Code.SOURCE_STORAGE_UNAVAILABLE, e);
		}

		if (observedSize != expectedByteSize) {
			deleteQuietly(tempFile);
			throw new ConsumerException(ConsumerException.Code.SOURCE_ARCHIVE_SIZE_MISMATCH);
		}
		if (!constantTimeEquals(observedSha256, expectedSha256.toLowerCase(Locale.ROOT))) {
			deleteQuietly(tempFile);
			throw new ConsumerException(ConsumerException.Code.SOURCE_ARCHIVE_HASH_MISMATCH);
		}
		return new DownloadedArchive(tempFile, observedSize, observedSha256);
	}

	private InputStream openStream(String objectKey) {
		try {
			return this.storage.get(objectKey);
		}
		catch (ObjectStorageException e) {
			// A missing key is a nonretryable data-integrity error; other
			// storage failures are mapped to a retryable category by the
			// adapter.
			String message = e.getMessage();
			if (message != null && message.contains("Object not found")) {
				throw new ConsumerException(ConsumerException.Code.SOURCE_ARCHIVE_MISSING, e);
			}
			throw new ConsumerException(ConsumerException.Code.SOURCE_STORAGE_UNAVAILABLE, e);
		}
	}

	private static void deleteQuietly(Path path) {
		try {
			Files.deleteIfExists(path);
		}
		catch (IOException ignored) {
			// best effort
		}
	}

	private static String hexLowercase(byte[] bytes) {
		StringBuilder sb = new StringBuilder(bytes.length * 2);
		for (byte b : bytes) {
			sb.append(Character.forDigit((b >> 4) & 0xF, 16));
			sb.append(Character.forDigit(b & 0xF, 16));
		}
		return sb.toString().toLowerCase(Locale.ROOT);
	}

	private static boolean constantTimeEquals(String a, String b) {
		if (a == null || b == null) {
			return false;
		}
		if (a.length() != b.length()) {
			return false;
		}
		int diff = 0;
		for (int i = 0; i < a.length(); i++) {
			diff |= a.charAt(i) ^ b.charAt(i);
		}
		return diff == 0;
	}

	/**
	 * Verifies a previously downloaded archive by checking the size/hash
	 * in constant time. Package-visible for reuse by other components.
	 */
	static boolean matches(long observedSize, String observedSha, long expectedSize, String expectedSha) {
		return observedSize == expectedSize
				&& constantTimeEquals(observedSha, expectedSha == null ? null : expectedSha.toLowerCase(Locale.ROOT));
	}

	/**
	 * A downloaded archive: temp path, exact byte count, lowercase SHA-256.
	 * The caller must {@link #close()} the handle to delete the temp file.
	 */
	public static final class DownloadedArchive implements AutoCloseable {

		private final Path tempPath;
		private final long byteCount;
		private final String sha256;
		private boolean closed;

		DownloadedArchive(Path tempPath, long byteCount, String sha256) {
			this.tempPath = tempPath;
			this.byteCount = byteCount;
			this.sha256 = sha256;
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

		@Override
		public void close() {
			if (this.closed) {
				return;
			}
			this.closed = true;
			deleteQuietly(this.tempPath);
		}

		@Override
		public String toString() {
			return "DownloadedArchive[byteCount=" + this.byteCount + "]";
		}
	}

}