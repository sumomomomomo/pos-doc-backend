package horse.sumomo.pos_doc_backend.ingestion.application;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import tools.jackson.databind.json.JsonMapper;

import horse.sumomo.pos_doc_backend.ingestion.archive.ArchiveFilenameParser;
import horse.sumomo.pos_doc_backend.ingestion.archive.ArchiveValidationException;
import horse.sumomo.pos_doc_backend.ingestion.archive.BoundedUploadSpooler;
import horse.sumomo.pos_doc_backend.ingestion.archive.SpooledUpload;
import horse.sumomo.pos_doc_backend.ingestion.archive.ValidatedArchive;
import horse.sumomo.pos_doc_backend.ingestion.archive.ZipArchiveValidator;
import horse.sumomo.pos_doc_backend.ingestion.messaging.IngestionRequestedMessage;
import horse.sumomo.pos_doc_backend.infrastructure.minio.MinioObjectStorage;
import horse.sumomo.pos_doc_backend.infrastructure.minio.ObjectStorageException;

/**
 * Orchestrates one secure ZIP intake request.
 *
 * <p>Owns, in order: request validation (multipart part, content type,
 * filename/eRef, optional policy number), bounded spooling with SHA-256,
 * ZIP/PDF validation, identifier and PII-free object-key generation, the
 * MinIO upload, the single database transaction via
 * {@link IntakeDatabaseService}, and scoped MinIO compensation when the
 * database transaction fails.
 *
 * <p>It never publishes to RabbitMQ; the outbox relay does that separately.
 * It never buffers the archive in heap memory: the spooler and the MinIO
 * adapter stream the file. No PII (filename, eRef, policy number, uploader,
 * hash, or bytes) is logged; only generated UUIDs and stable categories.
 */
@Service
public class PosArchiveIntakeService {

	static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("application/zip", "application/x-zip-compressed",
			"application/octet-stream");

	private static final Logger log = LoggerFactory.getLogger(PosArchiveIntakeService.class);

	private static final String ARCHIVE_CONTENT_TYPE = "application/zip";

	private final BoundedUploadSpooler spooler;
	private final ZipArchiveValidator validator;
	private final MinioObjectStorage storage;
	private final IntakeDatabaseService databaseService;
	private final CurrentUploaderProvider uploaderProvider;
	private final JsonMapper jsonMapper;
	private final String contextPath;

	public PosArchiveIntakeService(BoundedUploadSpooler spooler, ZipArchiveValidator validator,
			MinioObjectStorage storage, IntakeDatabaseService databaseService,
			CurrentUploaderProvider uploaderProvider, JsonMapper jsonMapper,
			@Value("${server.servlet.context-path:/api/v1}") String contextPath) {
		this.spooler = spooler;
		this.validator = validator;
		this.storage = storage;
		this.databaseService = databaseService;
		this.uploaderProvider = uploaderProvider;
		this.jsonMapper = jsonMapper;
		this.contextPath = contextPath;
	}

	/**
	 * Accepts and persists one upload request.
	 *
	 * @param file           the multipart {@code file} part
	 * @param policyNumber   optional policy number
	 * @return the persisted POS record and job identifiers
	 * @throws IntakeException          with a stable problem code
	 * @throws ArchiveValidationException with a stable category
	 */
	public UploadResult intake(MultipartFile file, String policyNumber) {
		if (file == null || file.isEmpty()) {
			throw new IntakeException(IntakeException.Code.MISSING_FILE);
		}

		ArchiveFilenameParser.ParsedFilename parsed = parseFilename(file.getOriginalFilename());
		String displayPolicy = validatePolicyNumber(policyNumber);
		String contentType = validateContentType(file.getContentType());

		try (SpooledUpload spooled = spool(file, spooler)) {
			ValidatedArchive validated = this.validator.validate(spooled.getTempPath(),
					spooled.getByteCount());

			Instant requestedAt = Instant.now();
			UUID storageObjectId = UUID.randomUUID();
			UUID posRecordId = UUID.randomUUID();
			UUID jobId = UUID.randomUUID();
			UUID outboxEventId = UUID.randomUUID();
			String objectKey = "archives/" + posRecordId + "/" + storageObjectId + ".zip";

			IngestionRequestedMessage message = IngestionRequestedMessage.of(outboxEventId, jobId, posRecordId,
					requestedAt);
			String payloadJson = this.jsonMapper.writeValueAsString(message);

			UploadCommand command = new UploadCommand(parsed.safeFilename(), parsed.displayEref(),
					displayPolicy, ARCHIVE_CONTENT_TYPE, spooled.getTempPath(), spooled.getByteCount(),
					spooled.getSha256(), validated.pdfCount(), this.uploaderProvider.currentUploader(),
					requestedAt, storageObjectId, posRecordId, jobId, outboxEventId, objectKey, payloadJson);

			try {
				uploadToMinIO(command);
			}
			catch (ObjectStorageException e) {
				// No database rows were created; nothing to compensate.
				log.warn("Intake failed at storage upload (category=storage-failure); posRecordId={}, jobId={}",
						posRecordId, jobId);
				throw new IntakeException(IntakeException.Code.INGESTION_INTAKE_FAILED, e);
			}

			try {
				this.databaseService.persist(command);
			}
			catch (RuntimeException e) {
				compensateMinIO(command, e);
				throw e;
			}

			log.info("Intake accepted: posRecordId={}, jobId={}, eventId={}, pdfCount={}", posRecordId, jobId,
					outboxEventId, validated.pdfCount());
			return new UploadResult(posRecordId, jobId);
		}
	}

	/**
	 * Returns the external base path for building the {@code Location}
	 * header of the upload response.
	 */
	public String contextPath() {
		return this.contextPath;
	}

	private static ArchiveFilenameParser.ParsedFilename parseFilename(String originalFilename) {
		try {
			return ArchiveFilenameParser.parse(originalFilename);
		}
		catch (ArchiveValidationException e) {
			if (e.getCategory() == ArchiveValidationException.Category.INVALID_ARCHIVE_FILENAME) {
				throw new IntakeException(IntakeException.Code.INVALID_ARCHIVE_FILENAME, e);
			}
			throw e;
		}
	}

	private static String validatePolicyNumber(String policyNumber) {
		if (policyNumber == null) {
			return null;
		}
		if (!policyNumber.matches(".*\\S.*")) {
			throw new IntakeException(IntakeException.Code.INVALID_POLICY_NUMBER);
		}
		try {
			horse.sumomo.pos_doc_backend.persistence.normalization.MetadataNormalizer
					.normalizeIdentifier(policyNumber);
		}
		catch (IllegalArgumentException e) {
			throw new IntakeException(IntakeException.Code.INVALID_POLICY_NUMBER, e);
		}
		return policyNumber;
	}

	private static String validateContentType(String contentType) {
		if (contentType == null || contentType.isBlank()) {
			// A missing content type is allowed; the ZIP signature and parser
			// are authoritative.
			return ARCHIVE_CONTENT_TYPE;
		}
		String normalized = contentType.split(";")[0].trim().toLowerCase(java.util.Locale.ROOT);
		if (!ALLOWED_CONTENT_TYPES.contains(normalized)) {
			throw new IntakeException(IntakeException.Code.UNSUPPORTED_ARCHIVE_TYPE);
		}
		return ARCHIVE_CONTENT_TYPE;
	}

	/**
	 * Spools the multipart stream, closing the request stream only if this
	 * method opened it. The servlet container owns multipart cleanup, so the
	 * source stream is left open for the container; only the spooler's own
	 * temp file is closed by the caller's try-with-resources.
	 */
	private static SpooledUpload spool(MultipartFile file, BoundedUploadSpooler spooler) {
		try (InputStream in = file.getInputStream()) {
			return spooler.spool(in);
		}
		catch (IOException e) {
			throw new IntakeException(IntakeException.Code.INGESTION_INTAKE_FAILED, e);
		}
	}

	private void uploadToMinIO(UploadCommand command) {
		try (InputStream in = Files.newInputStream(command.spooledPath())) {
			this.storage.put(command.objectKey(), in, command.compressedBytes(), ARCHIVE_CONTENT_TYPE);
		}
		catch (IOException e) {
			throw new ObjectStorageException("Failed to open spooled archive for upload", e);
		}
	}

	/**
	 * Deletes only the newly generated MinIO object when the database
	 * transaction fails. Compensation failure preserves the original cause
	 * and logs a sanitized orphan-recovery warning (the object key is
	 * UUID-only and may be logged for this purpose).
	 */
	private void compensateMinIO(UploadCommand command, RuntimeException original) {
		try {
			this.storage.delete(command.objectKey());
			log.debug("Compensated storage object after intake failure; posRecordId={}",
					command.posRecordId());
		}
		catch (ObjectStorageException compensationFailure) {
			log.warn("Orphaned MinIO object after intake failure (category=compensation-failed); "
					+ "objectKey={}", command.objectKey());
		}
		// Propagate the original categorized error; if it is not already an
		// IntakeException, wrap it.
		if (original instanceof IntakeException) {
			throw (IntakeException) original;
		}
		if (original instanceof ArchiveValidationException ave) {
			throw rethrowValidation(ave);
		}
		throw new IntakeException(IntakeException.Code.INGESTION_INTAKE_FAILED, original);
	}

	private static ArchiveValidationException rethrowValidation(ArchiveValidationException e) {
		return e;
	}

}
