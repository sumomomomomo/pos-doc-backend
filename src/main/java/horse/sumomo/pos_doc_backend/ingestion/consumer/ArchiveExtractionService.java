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
 *   <li>Stream the entry to a unique temp PDF, computing SHA-256, and
 *       enforcing the configured per-entry limit on every read.</li>
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
	 *                            source archive (used for ZIP validation)
	 * @param posRecordId         the POS record UUID
	 * @return the list of {@link ExtractedPdf} in central-directory order
	 * @throws ConsumerException on size, hash, ZIP, magic, or storage
	 *             failures
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
		try (ZipFile zipFile = openZip(sourceZipPath)) {
			var entries = zipFile.entries();
			int sequence = 0;
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				if (entry.isDirectory()) {
					continue;
				}
				ExtractedPdf extracted = extractOne(zipFile, entry, sequence, posRecordId);
				out.add(extracted);
				sequence++;
			}
		}
		catch (ArchiveValidationException e) {
			// Re-raise validation failures from extraction with the same
			// stable category.
			throw new ConsumerException(ConsumerException.Code.SOURCE_ARCHIVE_INVALID, e);
		}
		catch (IOException e) {
			throw new ConsumerException(ConsumerException.Code.SOURCE_STORAGE_UNAVAILABLE, e);
		}

		log.info("Archive extraction persisted PDFs (category=extraction-success); posRecordId={}, pdfCount={}",
				posRecordId, out.size());
		return out;
	}

	/**
	 * Deletes only the MinIO keys created during the current attempt. Used
	 * by the listener when a later step fails, to keep partial uploads out
	 * of the bucket. Logs only the UUIDs that identify the objects; raw
	 * object keys are UUID-only and may be logged for orphan recovery.
	 */
	public void compensate(List<ExtractedPdf> created) {
		if (created == null || created.isEmpty()) {
			return;
		}
		for (ExtractedPdf pdf : created) {
			try {
				this.storage.delete(pdf.objectKey());
			}
			catch (RuntimeException e) {
				log.warn("Compensation failure for document (category=compensation-failed); "
						+ "documentId={}, objectKey={}", pdf.documentId(), pdf.objectKey());
			}
		}
	}

	private ExtractedPdf extractOne(ZipFile zipFile, ZipEntry entry, int sequence, UUID posRecordId) {
		UUID documentId = DocumentIdentityDeriver.deriveDocumentId(posRecordId, sequence);
		UUID storageObjectId = DocumentIdentityDeriver.deriveStorageObjectId(documentId);
		String objectKey = DocumentIdentityDeriver.buildDocumentObjectKey(posRecordId, documentId);
		String filenameSegment = lastSegment(entry.getName());

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
			byteCount = streamPdfWithLimits(in, tempPdf);
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
		deleteQuietly(tempPdf);

		Instant now = Instant.now();
		return new ExtractedPdf(documentId, storageObjectId, objectKey, filenameSegment, byteCount, sha256,
				sequence, now);
	}

	private long streamPdfWithLimits(InputStream in, Path tempPdf) {
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
			out.write(magic, 0, magicRead);
			digest.update(magic, 0, magicRead);

			byte[] buffer = new byte[BUFFER_SIZE];
			int read;
			while ((read = in.read(buffer)) != -1) {
				bytesRead += read;
				if (bytesRead > this.limits.maxEntryBytes()) {
					throw new ConsumerException(ConsumerException.Code.SOURCE_ARCHIVE_INVALID);
				}
				out.write(buffer, 0, read);
				digest.update(buffer, 0, read);
			}
		}
		catch (IOException e) {
			throw new ConsumerException(ConsumerException.Code.EXTRACTION_TRANSIENT_FAILURE, e);
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

	private static ZipFile openZip(Path zipPath) {
		try {
			return new ZipFile(zipPath.toFile());
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
	 * One extracted PDF, ready to be persisted.
	 *
	 * @param documentId      deterministic document UUID
	 * @param storageObjectId deterministic storage-object UUID
	 * @param objectKey       the PII-free MinIO object key
	 * @param filenameSegment the final path segment of the source entry
	 *                        (kept as metadata only)
	 * @param byteSize        exact byte count of the PDF
	 * @param sha256          lowercase hex SHA-256
	 * @param sequence        zero-based central-directory sequence
	 * @param createdAt       the upload instant
	 */
	public record ExtractedPdf(UUID documentId, UUID storageObjectId, String objectKey, String filenameSegment,
			long byteSize, String sha256, int sequence, Instant createdAt) {
	}

}