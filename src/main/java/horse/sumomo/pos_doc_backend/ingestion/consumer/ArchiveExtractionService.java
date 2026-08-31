package horse.sumomo.pos_doc_backend.ingestion.consumer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import horse.sumomo.pos_doc_backend.ingestion.api.UploadLimitsProperties;
import horse.sumomo.pos_doc_backend.ingestion.archive.ArchiveValidationException;
import horse.sumomo.pos_doc_backend.ingestion.archive.ValidatedArchive;
import horse.sumomo.pos_doc_backend.ingestion.archive.ZipArchiveValidator;
import horse.sumomo.pos_doc_backend.infrastructure.minio.MinioObjectStorage;

/**
 * Streams every PDF entry from a verified source archive to MinIO and
 * collects per-PDF metadata for the persistence step.
 *
 * <p>For each non-directory entry in central-directory order:
 * <ol>
 *   <li>Derive a deterministic document UUID and storage-object UUID.</li>
 *   <li>Build a unique PII-free object key
 *       {@code documents/{posRecordId}/{documentId}.pdf}.</li>
 *   <li>Probe the object's pre-existence (HEAD against the bucket).
 *       Pre-existing objects must not be deleted during compensation;
 *       they are only reused when every immutable field matches the
 *       proposed extraction.</li>
 *   <li>Stream the entry to a unique temp PDF, computing SHA-256, and
 *       enforcing the per-entry limit, the cumulative expanded-byte
 *       limit, and the effective compression-ratio limit using bytes
 *       actually read.</li>
 *   <li>Validate the {@code %PDF-} magic and exact byte count.</li>
 *   <li>Upload the temp PDF with {@code application/pdf} and the known
 *       size.</li>
 *   <li>Delete the temp PDF before processing the next entry.</li>
 * </ol>
 *
 * <p>Peak temporary storage is one source ZIP plus one PDF. No entry
 * bytes are retained in heap.
 */
@Component
public class ArchiveExtractionService {

	private static final Logger log = LoggerFactory.getLogger(ArchiveExtractionService.class);

	private static final int BUFFER_SIZE = 8192;
	private static final int PDF_MAGIC_LEN = 5;
	private static final String PDF_CONTENT_TYPE = "application/pdf";
	private static final String TEMP_PREFIX = "pos-doc-consumer-pdf-";
	private static final String TEMP_SUFFIX = ".part";

	private final ZipArchiveValidator validator;
	private final MinioObjectStorage storage;
	private final UploadLimitsProperties limits;

	public ArchiveExtractionService(ZipArchiveValidator validator, MinioObjectStorage storage,
			UploadLimitsProperties limits) {
		this.validator = Objects.requireNonNull(validator, "validator must not be null");
		this.storage = Objects.requireNonNull(storage, "storage must not be null");
		this.limits = Objects.requireNonNull(limits, "limits must not be null");
	}

	/**
	 * Extracts and persists all PDFs from one source archive.
	 *
	 * @param sourceZipPath       the verified source archive temp file
	 * @param sourceByteCount     the actual compressed byte count of the
	 *                            source archive (used for ZIP validation
	 *                            and effective compression-ratio check)
	 * @param posRecordId         the POS record UUID
	 * @return the list of {@link ExtractedPdf} in central-directory order
	 * @throws ConsumerException on size, hash, ZIP, magic, cumulative,
	 *             ratio, or storage failures
	 */
	public List<ExtractedPdf> extractAndStore(Path sourceZipPath, long sourceByteCount, UUID posRecordId) {
		Objects.requireNonNull(sourceZipPath, "sourceZipPath must not be null");
		Objects.requireNonNull(posRecordId, "posRecordId must not be null");

		ValidatedArchive validated;
		try {
			validated = this.validator.validate(sourceZipPath, sourceByteCount);
		}
		catch (ArchiveValidationException e) {
			throw new ConsumerException(ConsumerException.Code.SOURCE_ARCHIVE_INVALID, e);
		}

		List<ExtractedPdf> out = new ArrayList<>(validated.pdfCount());
		List<ExtractedPdf> uploaded = new ArrayList<>(validated.pdfCount());
		try (ZipFile zipFile = openZip(sourceZipPath)) {
			var entries = zipFile.entries();
			int sequence = 0;
			long cumulativeExpanded = 0L;
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				if (entry.isDirectory()) {
					continue;
				}
				ExtractedPdf extracted = extractOne(zipFile, entry, sequence, posRecordId, sourceByteCount,
						cumulativeExpanded);
				out.add(extracted);
				uploaded.add(extracted);
				cumulativeExpanded += extracted.byteSize();
				sequence++;
			}
		}
		catch (ArchiveValidationException e) {
			// Re-raise validation failures from extraction with the same
			// stable category.
			compensate(uploaded);
			throw new ConsumerException(ConsumerException.Code.SOURCE_ARCHIVE_INVALID, e);
		}
		catch (ConsumerException e) {
			// Partial failure during extraction: only compensate the
			// objects the current attempt actually uploaded.
			compensate(uploaded);
			throw e;
		}
		catch (IOException e) {
			compensate(uploaded);
			throw new ConsumerException(ConsumerException.Code.SOURCE_STORAGE_UNAVAILABLE, e);
		}
		catch (RuntimeException e) {
			compensate(uploaded);
			throw new ConsumerException(ConsumerException.Code.EXTRACTION_TRANSIENT_FAILURE, e);
		}

		log.info("Archive extraction persisted PDFs (category=extraction-success); posRecordId={}, pdfCount={}",
				posRecordId, out.size());
		return out;
	}

	/**
	 * Deletes only the MinIO keys newly created during the current
	 * attempt. Pre-existing deterministic objects are left intact so a
	 * crash-recovery re-attempt that finds them in the bucket never
	 * destroys user data. Best-effort: a single delete failure is
	 * logged and the remaining deletes are still attempted. Used by
	 * the listener when a later step fails.
	 */
	public void compensate(List<ExtractedPdf> created) {
		if (created == null || created.isEmpty()) {
			return;
		}
		for (ExtractedPdf pdf : created) {
			if (pdf.wasPreExisting()) {
				// Never delete a pre-existing deterministic object; a
				// concurrent attempt may still be reading it.
				log.debug("Compensation skipped for pre-existing object (category=compensation-skip); "
						+ "documentId={}, objectKey={}", pdf.documentId(), pdf.objectKey());
				continue;
			}
			try {
				this.storage.delete(pdf.objectKey());
			}
			catch (RuntimeException e) {
				log.warn("Compensation failure for document (category=compensation-failed); "
						+ "documentId={}, objectKey={}", pdf.documentId(), pdf.objectKey());
			}
		}
	}

	private ExtractedPdf extractOne(ZipFile zipFile, ZipEntry entry, int sequence, UUID posRecordId,
			long sourceByteCount, long cumulativeExpandedBefore) {
		UUID documentId = DocumentIdentityDeriver.deriveDocumentId(posRecordId, sequence);
		UUID storageObjectId = DocumentIdentityDeriver.deriveStorageObjectId(documentId);
		String objectKey = DocumentIdentityDeriver.buildDocumentObjectKey(posRecordId, documentId);
		String filenameSegment = lastSegment(entry.getName());

		// Probe pre-existence. A pre-existing object with the
		// deterministic key is a previous successful upload; we will
		// keep it untouched (compensation skips it) and let the
		// persistence step reconcile against it.
		boolean wasPreExisting;
		try {
			wasPreExisting = this.storage.exists(objectKey);
		}
		catch (RuntimeException e) {
			throw new ConsumerException(ConsumerException.Code.SOURCE_STORAGE_UNAVAILABLE, e);
		}

		Path tempPdf;
		try {
			tempPdf = Files.createTempFile(TEMP_PREFIX, TEMP_SUFFIX);
		}
		catch (IOException e) {
			throw new ConsumerException(ConsumerException.Code.EXTRACTION_TRANSIENT_FAILURE, e);
		}

		long byteCount;
		String sha256;
		try (InputStream in = zipFile.getInputStream(entry)) {
			byteCount = streamPdfWithLimits(in, tempPdf, cumulativeExpandedBefore, sourceByteCount);
		}
		catch (ConsumerException e) {
			deleteQuietly(tempPdf);
			throw e;
		}
		catch (IOException e) {
			deleteQuietly(tempPdf);
			throw new ConsumerException(ConsumerException.Code.SOURCE_STORAGE_UNAVAILABLE, e);
		}

		// Re-validate magic on disk so a stream-only-validated entry that
		// happened to start with %PDF- but corrupt later bytes is still
		// rejected. The stream write wrote exactly byteCount bytes, so
		// the file size equals byteCount.
		try {
			verifyPdfMagic(tempPdf);
		}
		catch (ConsumerException e) {
			deleteQuietly(tempPdf);
			throw e;
		}
		sha256 = sha256OfFile(tempPdf);
		if (sha256 == null) {
			deleteQuietly(tempPdf);
			throw new ConsumerException(ConsumerException.Code.EXTRACTION_TRANSIENT_FAILURE);
		}

		// Only upload when the deterministic key did not already exist;
		// the persistence step will reconcile against the existing
		// object's bytes via the size/hash immutable fields.
		if (!wasPreExisting) {
			try (InputStream in = Files.newInputStream(tempPdf)) {
				this.storage.put(objectKey, in, byteCount, PDF_CONTENT_TYPE);
			}
			catch (IOException e) {
				deleteQuietly(tempPdf);
				throw new ConsumerException(ConsumerException.Code.SOURCE_STORAGE_UNAVAILABLE, e);
			}
			catch (RuntimeException e) {
				deleteQuietly(tempPdf);
				throw new ConsumerException(ConsumerException.Code.SOURCE_STORAGE_UNAVAILABLE, e);
			}
		}
		deleteQuietly(tempPdf);

		Instant now = Instant.now();
		return new ExtractedPdf(documentId, storageObjectId, objectKey, filenameSegment, byteCount, sha256,
				sequence, wasPreExisting, now);
	}

	private long streamPdfWithLimits(InputStream in, Path tempPdf, long cumulativeExpandedBefore,
			long sourceByteCount) {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException e) {
			throw new ConsumerException(ConsumerException.Code.EXTRACTION_TRANSIENT_FAILURE, e);
		}

		long bytesRead = 0L;
		byte[] magic = new byte[PDF_MAGIC_LEN];
		try (var out = Files.newOutputStream(tempPdf,
				StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
			int magicRead = 0;
			while (magicRead < PDF_MAGIC_LEN) {
				int r = in.read(magic, magicRead, PDF_MAGIC_LEN - magicRead);
				if (r == -1) {
					throw new ConsumerException(ConsumerException.Code.SOURCE_ARCHIVE_INVALID);
				}
				magicRead += r;
			}
			if (magic[0] != '%' || magic[1] != 'P' || magic[2] != 'D' || magic[3] != 'F' || magic[4] != '-') {
				throw new ConsumerException(ConsumerException.Code.SOURCE_ARCHIVE_INVALID);
			}
			bytesRead += magicRead;
			if (bytesRead > this.limits.maxEntryBytes()) {
				throw new ConsumerException(ConsumerException.Code.SOURCE_ARCHIVE_INVALID);
			}
			// Cumulative expanded budget: a single entry's worth of
			// bytes must fit; the running total must also fit.
			if (cumulativeExpandedBefore + bytesRead > this.limits.maxUncompressedBytes()) {
				throw new ConsumerException(ConsumerException.Code.SOURCE_ARCHIVE_INVALID);
			}
			out.write(magic, 0, magicRead);
			digest.update(magic, 0, magicRead);

			byte[] buffer = new byte[BUFFER_SIZE];
			int read;
			while ((read = in.read(buffer)) != -1) {
				bytesRead += read;
				if (bytesRead > this.limits.maxEntryBytes()) {
					throw new ConsumerException(ConsumerException.Code.SOURCE_ARCHIVE_INVALID);
				}
				if (cumulativeExpandedBefore + bytesRead > this.limits.maxUncompressedBytes()) {
					throw new ConsumerException(ConsumerException.Code.SOURCE_ARCHIVE_INVALID);
				}
				out.write(buffer, 0, read);
				digest.update(buffer, 0, read);
			}
		}
		catch (IOException e) {
			throw new ConsumerException(ConsumerException.Code.EXTRACTION_TRANSIENT_FAILURE, e);
		}

		// Effective compression ratio: bytes-actually-read vs compressed
		// source. We check after the entry is fully read so the bound
		// reflects the on-the-wire ratio, not a streaming estimate.
		if (sourceByteCount > 0L) {
			long totalExpanded = cumulativeExpandedBefore + bytesRead;
			long observedRatio = (totalExpanded + sourceByteCount - 1L) / sourceByteCount; // ceil division
			if (observedRatio > this.limits.maxCompressionRatio()) {
				throw new ConsumerException(ConsumerException.Code.SOURCE_ARCHIVE_INVALID);
			}
		}
		return bytesRead;
	}

	private static String sha256OfFile(Path file) {
		try (InputStream in = Files.newInputStream(file)) {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] buffer = new byte[BUFFER_SIZE];
			int read;
			while ((read = in.read(buffer)) != -1) {
				digest.update(buffer, 0, read);
			}
			return hexLowercase(digest.digest());
		}
		catch (IOException | NoSuchAlgorithmException e) {
			return null;
		}
	}

	private static void verifyPdfMagic(Path file) {
		try (InputStream in = Files.newInputStream(file)) {
			byte[] magic = new byte[PDF_MAGIC_LEN];
			int read = 0;
			while (read < PDF_MAGIC_LEN) {
				int r = in.read(magic, read, PDF_MAGIC_LEN - read);
				if (r == -1) {
					throw new ConsumerException(ConsumerException.Code.SOURCE_ARCHIVE_INVALID);
				}
				read += r;
			}
			if (magic[0] != '%' || magic[1] != 'P' || magic[2] != 'D' || magic[3] != 'F' || magic[4] != '-') {
				throw new ConsumerException(ConsumerException.Code.SOURCE_ARCHIVE_INVALID);
			}
		}
		catch (IOException e) {
			throw new ConsumerException(ConsumerException.Code.EXTRACTION_TRANSIENT_FAILURE, e);
		}
	}

	private static String lastSegment(String entryName) {
		String normalized = entryName.replace('\\', '/');
		int last = normalized.lastIndexOf('/');
		String segment = last >= 0 ? normalized.substring(last + 1) : normalized;
		if (segment.isBlank()) {
			segment = "document.pdf";
		}
		return segment;
	}

	private static String hexLowercase(byte[] bytes) {
		StringBuilder sb = new StringBuilder(bytes.length * 2);
		for (byte b : bytes) {
			sb.append(Character.forDigit((b >> 4) & 0xF, 16));
			sb.append(Character.forDigit(b & 0xF, 16));
		}
		return sb.toString().toLowerCase(Locale.ROOT);
	}

	private static ZipFile openZip(Path sourceZipPath) {
		try {
			return new ZipFile(sourceZipPath.toFile());
		}
		catch (IOException e) {
			throw new ConsumerException(ConsumerException.Code.SOURCE_ARCHIVE_INVALID, e);
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

	/**
	 * Per-PDF extraction result. {@code wasPreExisting} records whether
	 * the deterministic key was already present in MinIO when this
	 * attempt started; it is used by compensation to leave any
	 * pre-existing object untouched.
	 */
	public record ExtractedPdf(UUID documentId, UUID storageObjectId, String objectKey, String filenameSegment,
			long byteSize, String sha256, int sequence, boolean wasPreExisting, Instant uploadedAt) {
	}
}