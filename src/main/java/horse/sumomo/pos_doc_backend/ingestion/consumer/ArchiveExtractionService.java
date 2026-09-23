package horse.sumomo.pos_doc_backend.ingestion.consumer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import horse.sumomo.pos_doc_backend.ingestion.api.UploadLimitsProperties;
import horse.sumomo.pos_doc_backend.ingestion.archive.ArchiveValidationException;
import horse.sumomo.pos_doc_backend.ingestion.archive.PdfEntryDecoder;
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
 *   <li>Probe the object's pre-existence (HEAD against the bucket). The
 *       pre-existence flag is recorded on the {@link ExtractedPdf} so
 *       compensation can skip it; the extractor still <em>always</em>
 *       uploads the freshly extracted bytes so the bucket reflects the
 *       verified immutable source archive. The persistence step then
 *       reconciles against the new bytes via the immutable storage /
 *       document fields.</li>
 *   <li>Decode the entry through the shared {@link PdfEntryDecoder} (a raw
 *       PDF, or a strict Java-serialized {@code byte[]}) into a unique temp
 *       PDF, computing SHA-256 over the <em>normalized</em> PDF bytes and
 *       enforcing the per-entry limit, the cumulative expanded-byte limit,
 *       and the compression-ratio limit using the raw uncompressed entry
 *       bytes (including the 27-byte wrapper for the serialized form).</li>
 *   <li>Re-validate that the temp file begins with {@code %PDF-} (byte zero
 *       of the normalized PDF).</li>
 *   <li>Upload the normalized temp PDF with {@code application/pdf} and its
 *       normalized size, overwriting any object already at the deterministic
 *       key.</li>
 *   <li>Delete the temp PDF before processing the next entry.</li>
 * </ol>
 *
 * <p>Peak temporary storage is one source ZIP plus one PDF. No entry
 * bytes are retained in heap.
 */
@Component
public class ArchiveExtractionService {

	private static final Logger log = LoggerFactory.getLogger(ArchiveExtractionService.class);

	private static final int PDF_MAGIC_LEN = 5;
	private static final String PDF_CONTENT_TYPE = "application/pdf";
	private static final String TEMP_PREFIX = "pos-doc-consumer-pdf-";
	private static final String TEMP_SUFFIX = ".part";

	private final ZipArchiveValidator validator;
	private final PdfEntryDecoder decoder;
	private final MinioObjectStorage storage;
	private final UploadLimitsProperties limits;

	public ArchiveExtractionService(ZipArchiveValidator validator, MinioObjectStorage storage,
			UploadLimitsProperties limits) {
		this.validator = Objects.requireNonNull(validator, "validator must not be null");
		this.storage = Objects.requireNonNull(storage, "storage must not be null");
		this.limits = Objects.requireNonNull(limits, "limits must not be null");
		this.decoder = new PdfEntryDecoder();
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
				ExtractedResult result = extractOne(zipFile, entry, sequence, posRecordId, sourceByteCount,
						cumulativeExpanded);
				out.add(result.pdf());
				uploaded.add(result.pdf());
				// Cumulative security accounting uses the raw uncompressed
				// entry bytes, including the 27-byte wrapper when present.
				cumulativeExpanded += result.sourceEntryBytes();
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
	 * destroys user data, and so that a successful retry that
	 * legitimately overwrote the same key leaves the bucket in a
	 * consistent state. Best-effort: a single delete failure is logged
	 * and the remaining deletes are still attempted. Used by the
	 * listener when a later step fails.
	 */
	public void compensate(List<ExtractedPdf> created) {
		if (created == null || created.isEmpty()) {
			return;
		}
		for (ExtractedPdf pdf : created) {
			if (pdf.wasPreExisting()) {
				// Never delete a pre-existing deterministic object: a
				// concurrent attempt may still be reading it and the
				// current attempt may have only refreshed the bytes,
				// not created the key.
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

	private ExtractedResult extractOne(ZipFile zipFile, ZipEntry entry, int sequence, UUID posRecordId,
			long sourceByteCount, long cumulativeExpandedBefore) {
		UUID documentId = DocumentIdentityDeriver.deriveDocumentId(posRecordId, sequence);
		UUID storageObjectId = DocumentIdentityDeriver.deriveStorageObjectId(documentId);
		String objectKey = DocumentIdentityDeriver.buildDocumentObjectKey(posRecordId, documentId);
		String filenameSegment = lastSegment(entry.getName());

		// Probe pre-existence. A pre-existing object with the
		// deterministic key is a previous successful upload. We still
		// overwrite it with the freshly extracted bytes so the bucket
		// always reflects the verified immutable source archive; the
		// flag is retained on the ExtractedPdf so compensation can
		// skip it on a later failure.
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

		NormalizedPdf normalized;
		try (InputStream in = zipFile.getInputStream(entry)) {
			normalized = streamNormalizedPdf(in, tempPdf, cumulativeExpandedBefore, sourceByteCount);
		}
		catch (ConsumerException e) {
			deleteQuietly(tempPdf);
			throw e;
		}
		catch (IOException e) {
			deleteQuietly(tempPdf);
			throw new ConsumerException(ConsumerException.Code.SOURCE_STORAGE_UNAVAILABLE, e);
		}

		// Re-validate that the normalized temp file begins with %PDF- (byte
		// zero). The decoder already guarantees this, but a second on-disk
		// check is cheap defense-in-depth before upload.
		try {
			verifyPdfMagic(tempPdf);
		}
		catch (ConsumerException e) {
			deleteQuietly(tempPdf);
			throw e;
		}

		// Always upload the normalized PDF so the bucket reflects the verified
		// immutable source archive. Pre-existence was already probed; the flag
		// on ExtractedPdf tells compensation to skip deletion of a key that
		// existed before this attempt.
		try (InputStream in = Files.newInputStream(tempPdf)) {
			this.storage.put(objectKey, in, normalized.normalizedBytes(), PDF_CONTENT_TYPE);
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
		ExtractedPdf pdf = new ExtractedPdf(documentId, storageObjectId, objectKey, filenameSegment,
				normalized.normalizedBytes(), normalized.sha256(), sequence, wasPreExisting, now);
		return new ExtractedResult(pdf, normalized.sourceEntryBytes());
	}

	private NormalizedPdf streamNormalizedPdf(InputStream in, Path tempPdf, long cumulativeExpandedBefore,
			long sourceByteCount) {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException e) {
			throw new ConsumerException(ConsumerException.Code.EXTRACTION_TRANSIENT_FAILURE, e);
		}

		PdfEntryDecoder.DecodeResult result;
		try (OutputStream fileOut = Files.newOutputStream(tempPdf,
				StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
				DigestOutputStream out = new DigestOutputStream(fileOut, digest)) {
			try {
				result = this.decoder.decode(in, out, this.limits.maxEntryBytes());
			}
			catch (ArchiveValidationException e) {
				// A malformed or unrecognized entry is a permanent
				// source-archive failure, not a transient one.
				throw new ConsumerException(ConsumerException.Code.SOURCE_ARCHIVE_INVALID, e);
			}
		}
		catch (IOException e) {
			throw new ConsumerException(ConsumerException.Code.SOURCE_STORAGE_UNAVAILABLE, e);
		}

		long sourceEntryBytes = result.sourceBytesRead();
		long normalizedBytes = result.pdfBytesWritten();

		// Security limits use the RAW uncompressed entry bytes (including the
		// 27-byte wrapper for the serialized form).
		if (cumulativeExpandedBefore + sourceEntryBytes > this.limits.maxUncompressedBytes()) {
			throw new ConsumerException(ConsumerException.Code.SOURCE_ARCHIVE_INVALID);
		}
		// Effective compression ratio: raw entry bytes vs compressed source.
		if (sourceByteCount > 0L) {
			long totalExpanded = cumulativeExpandedBefore + sourceEntryBytes;
			long observedRatio = (totalExpanded + sourceByteCount - 1L) / sourceByteCount; // ceil division
			if (observedRatio > this.limits.maxCompressionRatio()) {
				throw new ConsumerException(ConsumerException.Code.SOURCE_ARCHIVE_INVALID);
			}
		}
		return new NormalizedPdf(normalizedBytes, sourceEntryBytes, hexLowercase(digest.digest()));
	}

	private static void verifyPdfMagic(Path file) {
		try (InputStream in = Files.newInputStream(file)) {
			byte[] head = new byte[PDF_MAGIC_LEN];
			int read = 0;
			while (read < PDF_MAGIC_LEN) {
				int r = in.read(head, read, PDF_MAGIC_LEN - read);
				if (r == -1) {
					throw new ConsumerException(ConsumerException.Code.SOURCE_ARCHIVE_INVALID);
				}
				read += r;
			}
			if (head[0] != '%' || head[1] != 'P' || head[2] != 'D' || head[3] != 'F' || head[4] != '-') {
				throw new ConsumerException(ConsumerException.Code.SOURCE_ARCHIVE_INVALID);
			}
		}
		catch (IOException e) {
			throw new ConsumerException(ConsumerException.Code.EXTRACTION_TRANSIENT_FAILURE, e);
		}
	}

	private static String lastSegment(String entryName) {
		int idx = entryName.lastIndexOf('/');
		String tail = idx < 0 ? entryName : entryName.substring(idx + 1);
		// Preserve the original case: candidate selection matches the basename
		// case-sensitively against "LAPPe.pdf", so the stored name must keep case.
		return tail;
	}

	private static String hexLowercase(byte[] bytes) {
		StringBuilder sb = new StringBuilder(bytes.length * 2);
		for (byte b : bytes) {
			sb.append(Character.forDigit((b >> 4) & 0xF, 16));
			sb.append(Character.forDigit(b & 0xF, 16));
		}
		return sb.toString();
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
			// Best-effort cleanup; the next run will overwrite the
			// temp file via TRUNCATE_EXISTING.
		}
	}

	/**
	 * Internal result of streaming one entry through the decoder: the
	 * normalized PDF byte count, the raw source-entry byte count (including
	 * the 27-byte wrapper when present), and the SHA-256 of the normalized
	 * PDF bytes.
	 */
	private record NormalizedPdf(long normalizedBytes, long sourceEntryBytes, String sha256) {
	}

	/**
	 * Per-entry extraction result: the public/persisted {@link ExtractedPdf}
	 * (whose byte size is the normalized size) plus the raw source-entry byte
	 * count used for cumulative security-limit accounting.
	 */
	private record ExtractedResult(ExtractedPdf pdf, long sourceEntryBytes) {
	}

	/**
	 * Per-PDF extraction result. {@code wasPreExisting} records whether
	 * the deterministic key was already present in MinIO when this
	 * attempt began. The extractor still <em>always</em> uploads the
	 * freshly extracted bytes, so the flag's only remaining purpose is
	 * to drive compensation: only keys newly created by this attempt
	 * may be deleted on a later failure.
	 */
	public record ExtractedPdf(UUID documentId, UUID storageObjectId, String objectKey, String filenameSegment,
			long byteSize, String sha256, int sequence, boolean wasPreExisting, Instant uploadedAt) {
	}
}