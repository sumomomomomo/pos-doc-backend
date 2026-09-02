package horse.sumomo.pos_doc_backend.rendering.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import horse.sumomo.pos_doc_backend.infrastructure.minio.MinioObjectStorage;
import horse.sumomo.pos_doc_backend.infrastructure.minio.ObjectStorageException;
import horse.sumomo.pos_doc_backend.rendering.api.FirstPageRenderingProperties;
import horse.sumomo.pos_doc_backend.rendering.model.DocumentRenderSource;

/**
 * Streams one PDF object from MinIO to a unique temporary file while
 * enforcing the declared size, the configured maximum, and the SHA-256
 * digest.
 *
 * <p>The download is bounded: the actual bytes are counted as they are read;
 * one byte over either the declared size or the configured limit aborts the
 * download. The temporary file is deleted on every path — success, oversize,
 * hash mismatch, size mismatch, IO failure — so no partial copy is ever left
 * on disk after a failure. On success the caller receives a
 * {@link MaterializedPdf} handle whose {@link AutoCloseable#close()} deletes
 * the completed PDF temp file.
 *
 * <p>Digest comparison uses {@link MessageDigest#isEqual} for
 * constant-time semantics. The object key, filesystem path, hash, and
 * filename are never included in exception messages or
 * {@link #toString()}.
 */
@Component
public class StoredPdfMaterializer {

	private static final Logger log = LoggerFactory.getLogger(StoredPdfMaterializer.class);

	private static final int BUFFER_SIZE = 8192;
	private static final String TEMP_PREFIX = "pos-doc-render-pdf-";
	private static final String TEMP_SUFFIX = ".pdf.part";

	private final MinioObjectStorage storage;
	private final FirstPageRenderingProperties properties;

	public StoredPdfMaterializer(MinioObjectStorage storage, FirstPageRenderingProperties properties) {
		this.storage = Objects.requireNonNull(storage, "storage must not be null");
		this.properties = Objects.requireNonNull(properties, "properties must not be null");
	}

	/**
	 * Downloads and verifies the PDF.
	 *
	 * @param source the render source with the object key, expected size,
	 *        and expected SHA-256
	 * @return a {@link MaterializedPdf} handle owning the temp file
	 * @throws RenderingException with a stable code on any failure
	 */
	public MaterializedPdf materialize(DocumentRenderSource source) {
		Objects.requireNonNull(source, "source must not be null");

		Path tempFile;
		try {
			tempFile = Files.createTempFile(TEMP_PREFIX, TEMP_SUFFIX);
		}
		catch (IOException e) {
			throw new RenderingException(RenderingException.Code.TEMP_STORAGE_UNAVAILABLE, e);
		}

		long observedSize;
		try (InputStream in = openStream(source.objectKey())) {
			MessageDigest digest;
			try {
				digest = MessageDigest.getInstance("SHA-256");
			}
			catch (NoSuchAlgorithmException e) {
				deleteQuietly(tempFile);
				throw new RenderingException(RenderingException.Code.TEMP_STORAGE_UNAVAILABLE, e);
			}
			long bytesRead = 0L;
			byte[] buffer = new byte[BUFFER_SIZE];
			try (var out = Files.newOutputStream(tempFile,
					StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
				int read;
				while ((read = in.read(buffer)) != -1) {
					bytesRead += read;
					if (bytesRead > source.expectedByteSize() || bytesRead > this.properties.maxPdfBytes()) {
						throw new RenderingException(RenderingException.Code.PDF_SIZE_MISMATCH);
					}
					digest.update(buffer, 0, read);
					out.write(buffer, 0, read);
				}
			}
			observedSize = bytesRead;

			if (observedSize != source.expectedByteSize()) {
				deleteQuietly(tempFile);
				throw new RenderingException(RenderingException.Code.PDF_SIZE_MISMATCH);
			}

			byte[] observedDigest = digest.digest();
			byte[] expectedDigest = decodeHex(source.expectedSha256());
			if (!MessageDigest.isEqual(observedDigest, expectedDigest)) {
				deleteQuietly(tempFile);
				throw new RenderingException(RenderingException.Code.PDF_HASH_MISMATCH);
			}
		}
		catch (RenderingException e) {
			throw e;
		}
		catch (IOException e) {
			deleteQuietly(tempFile);
			throw new RenderingException(RenderingException.Code.PDF_STORAGE_UNAVAILABLE, e);
		}
		catch (RuntimeException e) {
			deleteQuietly(tempFile);
			throw new RenderingException(RenderingException.Code.PDF_STORAGE_UNAVAILABLE, e);
		}

		log.debug("PDF materialized; documentId={}, byteSize={}", source.documentId(), observedSize);
		return new MaterializedPdf(tempFile, observedSize);
	}

	private InputStream openStream(String objectKey) {
		try {
			return this.storage.get(objectKey);
		}
		catch (ObjectStorageException.MissingObjectException e) {
			throw new RenderingException(RenderingException.Code.PDF_OBJECT_MISSING, e);
		}
		catch (ObjectStorageException e) {
			throw new RenderingException(RenderingException.Code.PDF_STORAGE_UNAVAILABLE, e);
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

	private static byte[] decodeHex(String hex) {
		if (hex == null || hex.length() != 64) {
			throw new IllegalArgumentException("hex must be 64 characters");
		}
		byte[] bytes = new byte[32];
		for (int i = 0; i < 32; i++) {
			int hi = Character.digit(hex.charAt(i * 2), 16);
			int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
			if (hi < 0 || lo < 0) {
				throw new IllegalArgumentException("hex contains a non-hex character");
			}
			bytes[i] = (byte) ((hi << 4) | lo);
		}
		return bytes;
	}

	/**
	 * A materialized PDF: temp path and exact byte count. The caller must
	 * {@link #close()} the handle to delete the temp file.
	 *
	 * <p>{@link #toString()} contains no object key, filesystem path, hash,
	 * or filename.
	 */
	public static final class MaterializedPdf implements AutoCloseable {

		private final Path tempPath;
		private final long byteCount;
		private boolean closed;

		MaterializedPdf(Path tempPath, long byteCount) {
			this.tempPath = tempPath;
			this.byteCount = byteCount;
		}

		Path getTempPath() {
			return this.tempPath;
		}

		long getByteCount() {
			return this.byteCount;
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
			return "MaterializedPdf[byteCount=" + this.byteCount + "]";
		}
	}

}
