package horse.sumomo.pos_doc_backend.ingestion.archive;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.springframework.stereotype.Component;

import horse.sumomo.pos_doc_backend.ingestion.api.UploadLimitsProperties;

/**
 * Copies a multipart upload stream to a unique temporary file while
 * calculating SHA-256 and enforcing the compressed-byte limit.
 *
 * <p>Contract for the caller: the spooler reads from the supplied stream but
 * does <em>not</em> close it; closing the source stream remains the owner's
 * responsibility (the servlet container owns multipart temp cleanup). The
 * returned {@link SpooledUpload} is the spooler's own resource and must be
 * closed by the caller via try-with-resources.
 *
 * <p>Enforcement is independent of the servlet container: the actual bytes
 * read are counted and the copy aborts as soon as the configured limit is
 * exceeded. The declared size from the multipart API is never trusted. The
 * limit applies to the compressed archive as uploaded.
 *
 * <p>Failure hygiene: an empty upload is rejected; any failure (limit
 * exceeded, IO error, digest error) deletes the partial temporary file
 * before propagating. No temp path, original filename, hash, or content is
 * ever logged.
 */
@Component
public class BoundedUploadSpooler {

	private static final int BUFFER_SIZE = 8192;
	private static final String TEMP_PREFIX = "pos-doc-upload-";
	private static final String TEMP_SUFFIX = ".part";

	private final UploadLimitsProperties limits;

	public BoundedUploadSpooler(UploadLimitsProperties limits) {
		this.limits = limits;
	}

	/**
	 * Spools the upload. See class-level Javadoc for the stream-ownership
	 * contract.
	 *
	 * @param source the multipart input stream to read; not closed by this
	 *            method
	 * @return the spooled temporary file with byte count and SHA-256
	 * @throws ArchiveValidationException when the upload is empty, exceeds
	 *             the compressed limit, or cannot be spooled
	 */
	public SpooledUpload spool(InputStream source) {
		if (source == null) {
			throw new ArchiveValidationException(ArchiveValidationException.Category.EMPTY_UPLOAD,
					"upload stream is missing");
		}

		Path tempFile;
		try {
			tempFile = Files.createTempFile(TEMP_PREFIX, TEMP_SUFFIX);
		}
		catch (IOException e) {
			throw new ArchiveValidationException(ArchiveValidationException.Category.INVALID_ARCHIVE,
					"upload could not be spooled to a temporary file", e);
		}

		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			long bytesRead = 0L;
			byte[] buffer = new byte[BUFFER_SIZE];
			try (OutputStream out = Files.newOutputStream(tempFile)) {
				int read;
				while ((read = source.read(buffer)) != -1) {
					bytesRead += read;
					if (bytesRead > this.limits.maxCompressedBytes()) {
						throw new ArchiveValidationException(
								ArchiveValidationException.Category.ARCHIVE_TOO_LARGE,
								"compressed upload exceeds the size limit");
					}
					digest.update(buffer, 0, read);
					out.write(buffer, 0, read);
				}
			}

			if (bytesRead == 0L) {
				throw new ArchiveValidationException(ArchiveValidationException.Category.EMPTY_UPLOAD,
						"upload is empty");
			}

			byte[] hash = digest.digest();
			StringBuilder hex = new StringBuilder(hash.length * 2);
			for (byte b : hash) {
				hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
			}

			return new SpooledUpload(tempFile, bytesRead, hex.toString());
		}
		catch (NoSuchAlgorithmException e) {
			deleteQuietly(tempFile);
			throw new ArchiveValidationException(ArchiveValidationException.Category.INVALID_ARCHIVE,
					"SHA-256 digest could not be computed", e);
		}
		catch (IOException e) {
			deleteQuietly(tempFile);
			throw new ArchiveValidationException(ArchiveValidationException.Category.INVALID_ARCHIVE,
					"upload could not be spooled to a temporary file", e);
		}
		// ArchiveValidationException from the size/empty checks: delete the
		// partial file, then propagate unchanged.
		catch (ArchiveValidationException e) {
			deleteQuietly(tempFile);
			throw e;
		}
	}

	private static void deleteQuietly(Path path) {
		try {
			Files.deleteIfExists(path);
		}
		catch (IOException ignored) {
			// best effort; nothing else to do
		}
	}

}
