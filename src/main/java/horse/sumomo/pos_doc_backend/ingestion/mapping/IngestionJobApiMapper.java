package horse.sumomo.pos_doc_backend.ingestion.mapping;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import com.yourcompany.pos.api.model.IngestionJob;
import com.yourcompany.pos.api.model.JobStatus;

import horse.sumomo.pos_doc_backend.persistence.entity.IngestionJobEntity;

/**
 * Maps a persisted ingestion job to the generated API DTO.
 *
 * <p>Only scalar fields are read: the lazy {@code posRecord} association is
 * dereferenced only for its identifier (a primary-key projection, not a
 * collection), so no unrelated lazy collections are initialized. Error
 * information passes through unchanged; it is sanitized at the point it is
 * written by the (future) consumer, and a fresh queued job carries none.
 */
public final class IngestionJobApiMapper {

	private IngestionJobApiMapper() {
	}

	public static IngestionJob toDto(IngestionJobEntity entity) {
		return new IngestionJob(entity.getId(), entity.getPosRecord().getId(),
				JobStatus.valueOf(entity.getStatus().name()), (int) entity.getAttemptCount(),
				toOffsetDateTime(entity.getCreatedAt()))
				.errorCode(entity.getErrorCode())
				.errorMessage(entity.getErrorMessage())
				.startedAt(toOffsetDateTime(entity.getStartedAt()))
				.completedAt(toOffsetDateTime(entity.getCompletedAt()));
	}

	private static OffsetDateTime toOffsetDateTime(Instant instant) {
		return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
	}

}
