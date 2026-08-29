package horse.sumomo.pos_doc_backend.ingestion.application;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable input to the single database transaction that persists an
 * accepted upload.
 *
 * <p>Carries the validated request facts, the spooled file location, and the
 * identifiers (plus the PII-free object key and the already-serialized
 * outbox payload) generated before the MinIO upload. Nothing PII-sensitive
 * beyond the deliberately persisted display values (filename segment, eRef,
 * optional policy number, uploader subject) is carried here, and
 * {@link #toString()} exposes none of it.
 */
public record UploadCommand(String safeFilename, String displayEref, String displayPolicyNumber,
		String contentType, Path spooledPath, long compressedBytes, String sha256, int pdfCount,
		String uploaderSubject, Instant requestedAt, UUID storageObjectId, UUID posRecordId, UUID jobId,
		UUID outboxEventId, String objectKey, String payloadJson) {

	public UploadCommand {
		Objects.requireNonNull(safeFilename, "safeFilename must not be null");
		Objects.requireNonNull(displayEref, "displayEref must not be null");
		Objects.requireNonNull(contentType, "contentType must not be null");
		Objects.requireNonNull(spooledPath, "spooledPath must not be null");
		Objects.requireNonNull(sha256, "sha256 must not be null");
		Objects.requireNonNull(uploaderSubject, "uploaderSubject must not be null");
		Objects.requireNonNull(requestedAt, "requestedAt must not be null");
		Objects.requireNonNull(storageObjectId, "storageObjectId must not be null");
		Objects.requireNonNull(posRecordId, "posRecordId must not be null");
		Objects.requireNonNull(jobId, "jobId must not be null");
		Objects.requireNonNull(outboxEventId, "outboxEventId must not be null");
		Objects.requireNonNull(objectKey, "objectKey must not be null");
		Objects.requireNonNull(payloadJson, "payloadJson must not be null");
		if (compressedBytes < 0) {
			throw new IllegalArgumentException("compressedBytes must be >= 0");
		}
		if (pdfCount < 0) {
			throw new IllegalArgumentException("pdfCount must be >= 0");
		}
	}

	@Override
	public String toString() {
		return "UploadCommand[posRecordId=" + this.posRecordId + ", jobId=" + this.jobId + "]";
	}

}
