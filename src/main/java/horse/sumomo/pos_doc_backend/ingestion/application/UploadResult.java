package horse.sumomo.pos_doc_backend.ingestion.application;

import java.util.Objects;
import java.util.UUID;

/**
 * Result of a successfully persisted upload: the identifiers the client may
 * use to observe the record and its queued ingestion job.
 */
public record UploadResult(UUID posRecordId, UUID jobId) {

	public UploadResult {
		Objects.requireNonNull(posRecordId, "posRecordId must not be null");
		Objects.requireNonNull(jobId, "jobId must not be null");
	}

}
