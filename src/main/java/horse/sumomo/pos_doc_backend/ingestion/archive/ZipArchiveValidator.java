package horse.sumomo.pos_doc_backend.ingestion.archive;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import org.springframework.stereotype.Component;

import horse.sumomo.pos_doc_backend.ingestion.api.UploadLimitsProperties;

/**
 * Validates a spooled ZIP archive without extracting files to disk.
 *
 * <p>The ZIP represents one POS record and may contain multiple PDF files.
 * Every non-directory entry is fully read through a bounded buffer so the
 * enforced limits are based on bytes actually read, never on the
 * (possibly dishonest) {@link ZipEntry#getSize()} or
 * {@link ZipEntry#getCompressedSize()} values.
 *
 * <p>Validation rules:
 * <ul>
 *   <li>The file must begin with a recognized ZIP signature
 *       ({@code PK\003\004}, {@code PK\005\006} for an empty archive, or
 *       {@code PK\007\008} for a spanning archive); anything else is
 *       rejected <em>before a ZIP reader is opened</em> with the
 *       {@code UNSUPPORTED_ARCHIVE_TYPE} category (a file too small to carry
 *       a signature is a malformed/truncated ZIP and is rejected with
 *       {@code INVALID_ARCHIVE}). A {@code PK\005\006} file still ultimately
 *       fails because it contains no PDFs.</li>
 *   <li>Between 1 and the configured maximum of non-directory entries.</li>
 *   <li>Every non-directory entry must have a case-insensitive {@code .pdf}
 *       suffix and begin with the bytes {@code %PDF-}.</li>
 *   <li>Entry paths are normalized ({@code \} to {@code /}) and must not be
 *       absolute, Windows drive-prefixed, or contain empty, {@code .}, or
 *       {@code ..} segments.</li>
 *   <li>Normalized entry paths must be unique, compared case-insensitively
 *       ({@link Locale#ROOT}) because later extraction runs on a
 *       case-sensitive filesystem while archives may be created on Windows.</li>
 *   <li>Per-entry and total uncompressed byte limits based on bytes read,
 *       enforced <em>during</em> the read so a compression bomb is stopped at
 *       the first limit-breaking chunk and is never inflated to the full
 *       (possibly multi-gigabyte) size.</li>
 *   <li>Archive-level compression ratio (total uncompressed bytes divided by
 *       the spooled archive's actual compressed byte count) must not exceed
 *       the configured maximum once at least one compressed byte is known.
 *       An entry whose declared compressed size is zero but which produces
 *       output is rejected as suspicious.</li>
 * </ul>
 *
 * <p>Corrupt, truncated, or encrypted archives surface as
 * {@link ArchiveValidationException} with the {@code INVALID_ARCHIVE}
 * category. PDF contents are never retained.
 */
@Component
public class ZipArchiveValidator {

	// Package-visible so the unit test can pin the bounded-buffer chunk size
	// when proving the per-entry read stops at the first limit-breaking chunk.
	static final int BUFFER_SIZE = 8192;
	private static final int SIGNATURE_LEN = 4;
	private static final int PDF_MAGIC_LEN = 5;
	private static final String PDF_SUFFIX = ".pdf";

	private static final byte[] SIG_LOCAL_HEADER = {'P', 'K', 0x03, 0x04};
	private static final byte[] SIG_EMPTY_ARCHIVE = {'P', 'K', 0x05, 0x06};
	private static final byte[] SIG_SPANNING = {'P', 'K', 0x07, 0x08};

	private final UploadLimitsProperties limits;

	public ZipArchiveValidator(UploadLimitsProperties limits) {
		this.limits = limits;
	}

	/**
	 * Validates the spooled archive.
	 *
	 * @param spooledFile the temporary file holding the compressed archive
	 * @param archiveCompressedBytes the actual compressed byte count of the
	 *            spooled file, used for the archive-level ratio
	 * @return structural facts about the validated archive
	 * @throws ArchiveValidationException when any rule is violated
	 */
	public ValidatedArchive validate(Path spooledFile, long archiveCompressedBytes) {
		checkSignature(spooledFile);

		int pdfCount = 0;
		long totalUncompressed = 0L;
		Set<String> seenNormalizedPaths = new HashSet<>();

		try (ZipFile zipFile = openZipFile(spooledFile)) {
			var entries = zipFile.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();

				if (entry.isDirectory()) {
					validateDirectoryPath(entry.getName());
					continue;
				}

				if (pdfCount >= this.limits.maxFileEntries()) {
					throw invalid("too many file entries in the archive");
				}
				pdfCount = pdfCount + 1;

				String normalizedPath = validateAndNormalizePath(entry.getName());
				String lower = normalizedPath.toLowerCase(Locale.ROOT);
				if (!seenNormalizedPaths.add(lower)) {
					throw invalid("duplicate entry path in the archive");
				}

				if (!lower.endsWith(PDF_SUFFIX)) {
					throw invalid("archive contains a non-PDF entry");
				}

				// totalUncompressed is the running total of all entries read
				// before this one; the per-entry read is bounded by the
				// configured per-entry limit so a bomb stops at the first
				// limit-breaking chunk.
				long entryBytes = readAndValidateEntry(zipFile, entry, this.limits.maxEntryBytes());
				if (entry.getCompressedSize() == 0 && entryBytes > 0) {
					throw invalid("archive entry has a suspicious zero compressed size");
				}
				totalUncompressed += entryBytes;
				if (totalUncompressed > this.limits.maxUncompressedBytes()) {
					throw invalid("archive exceeds the total uncompressed size limit");
				}
				if (archiveCompressedBytes > 0
						&& totalUncompressed > archiveCompressedBytes * this.limits.maxCompressionRatio()) {
					throw invalid("archive exceeds the maximum compression ratio");
				}
			}
		}
		catch (ZipException e) {
			throw invalid("archive is corrupt, truncated, or encrypted", e);
		}
		catch (IOException e) {
			throw invalid("archive could not be read", e);
		}

		if (pdfCount == 0) {
			throw invalid("archive contains no PDF files");
		}

		return new ValidatedArchive(pdfCount, totalUncompressed);
	}

	private static void checkSignature(Path spooledFile) {
		byte[] header = new byte[SIGNATURE_LEN];
		try (InputStream in = Files.newInputStream(spooledFile)) {
			int read = in.readNBytes(header, 0, SIGNATURE_LEN);
			if (read < SIGNATURE_LEN) {
				// Fewer than four bytes: the file is too small to carry any
				// ZIP signature, i.e. it is malformed/truncated.
				throw invalid("archive is too small to be a ZIP file");
			}
		}
		catch (IOException e) {
			throw invalid("archive could not be read", e);
		}

		if (equalsHeader(header, SIG_LOCAL_HEADER) || equalsHeader(header, SIG_EMPTY_ARCHIVE)
				|| equalsHeader(header, SIG_SPANNING)) {
			return;
		}
		// Four or more bytes that are not a recognized ZIP signature: the
		// upload is not a ZIP at all, so it is an unsupported archive type
		// (415), not a syntactically-valid-but-unprocessable archive (422).
		throw new ArchiveValidationException(ArchiveValidationException.Category.UNSUPPORTED_ARCHIVE_TYPE,
				"archive does not carry a recognized ZIP signature");
	}

	private static ZipFile openZipFile(Path spooledFile) {
		try {
			return new ZipFile(spooledFile.toFile());
		}
		catch (IOException e) {
			throw invalid("archive is corrupt, truncated, or unsupported", e);
		}
	}

	/**
	 * Validates a file entry path and returns its normalized form. Rejects
	 * absolute paths, Windows drive-prefixed paths, and paths with empty,
	 * {@code .}, or {@code ..} segments.
	 */
	private static String validateAndNormalizePath(String rawName) {
		if (rawName == null || rawName.isBlank()) {
			throw invalid("archive entry has an empty path");
		}
		String normalized = rawName.replace('\\', '/');
		if (normalized.startsWith("/")) {
			throw invalid("archive entry uses an absolute path");
		}
		if (normalized.length() >= 3 && Character.isLetter(normalized.charAt(0))
				&& normalized.charAt(1) == ':') {
			throw invalid("archive entry uses a Windows drive-prefixed path");
		}
		validateSegments(normalized);
		return normalized;
	}

	private static void validateDirectoryPath(String rawName) {
		if (rawName == null || rawName.isBlank()) {
			throw invalid("archive directory entry has an empty path");
		}
		String normalized = rawName.replace('\\', '/');
		if (normalized.endsWith("/")) {
			normalized = normalized.substring(0, normalized.length() - 1);
		}
		if (normalized.isEmpty()) {
			throw invalid("archive directory entry references the archive root");
		}
		validateAndNormalizePath(normalized);
	}

	private static void validateSegments(String normalizedPath) {
		for (String segment : normalizedPath.split("/")) {
			if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
				throw invalid("archive entry path is unsafe");
			}
		}
	}

	/**
	 * Reads the entire entry through a bounded buffer, enforcing the PDF
	 * signature and the per-entry limit <em>on every chunk read</em>. The
	 * moment the running byte count of this entry would exceed
	 * {@code maxEntryBytes}, the read is aborted and an exception is raised
	 * so a compression bomb is never inflated beyond the first
	 * limit-breaking chunk. Returns the number of uncompressed bytes read.
	 */
	private static long readAndValidateEntry(ZipFile zipFile, ZipEntry entry, long maxEntryBytes) throws IOException {
		try (InputStream in = zipFile.getInputStream(entry)) {
			return readAndValidatePdf(in, maxEntryBytes);
		}
	}

	/**
	 * Reads a PDF entry body from {@code in} through a bounded buffer,
	 * enforcing the {@code %PDF-} signature and the per-entry limit on every
	 * chunk read. Package-visible and stream-oriented so the unit test can
	 * wrap a counting stream and prove the read stops at the first
	 * limit-breaking chunk.
	 *
	 * @param in the uncompressed entry stream
	 * @param maxEntryBytes the per-entry uncompressed byte limit
	 * @return the number of uncompressed bytes read (<= maxEntryBytes)
	 * @throws IOException when the underlying stream fails
	 */
	static long readAndValidatePdf(InputStream in, long maxEntryBytes) throws IOException {
		long bytesRead = 0L;
		byte[] magic = new byte[PDF_MAGIC_LEN];
		byte[] buffer = new byte[BUFFER_SIZE];

		int magicRead = 0;
		while (magicRead < PDF_MAGIC_LEN) {
			int r = in.read(magic, magicRead, PDF_MAGIC_LEN - magicRead);
			if (r == -1) {
				throw invalid("PDF entry is missing its signature bytes");
			}
			magicRead += r;
		}
		if (!startsWithPdfMagic(magic)) {
			throw invalid("archive entry does not begin with the PDF signature");
		}
		bytesRead += magicRead;

		int read;
		while ((read = in.read(buffer)) != -1) {
			bytesRead += read;
			if (bytesRead > maxEntryBytes) {
				// Stop at the first chunk that pushes this entry over its
				// per-entry limit; do not keep inflating the bomb.
				throw invalid("archive entry exceeds the per-entry size limit");
			}
		}
		return bytesRead;
	}

	private static boolean startsWithPdfMagic(byte[] magic) {
		return magic[0] == '%' && magic[1] == 'P' && magic[2] == 'D' && magic[3] == 'F' && magic[4] == '-';
	}

	private static boolean equalsHeader(byte[] actual, byte[] expected) {
		for (int i = 0; i < SIGNATURE_LEN; i++) {
			if (actual[i] != expected[i]) {
				return false;
			}
		}
		return true;
	}

	private static ArchiveValidationException invalid(String message) {
		return new ArchiveValidationException(ArchiveValidationException.Category.INVALID_ARCHIVE, message);
	}

	private static ArchiveValidationException invalid(String message, Throwable cause) {
		return new ArchiveValidationException(ArchiveValidationException.Category.INVALID_ARCHIVE, message, cause);
	}

}
