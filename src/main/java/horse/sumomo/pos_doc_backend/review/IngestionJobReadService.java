package horse.sumomo.pos_doc_backend.review;

import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yourcompany.pos.api.model.IngestionJob;

import horse.sumomo.pos_doc_backend.ingestion.application.IntakeException;
import horse.sumomo.pos_doc_backend.ingestion.mapping.IngestionJobApiMapper;
import horse.sumomo.pos_doc_backend.persistence.entity.IngestionJobEntity;
import horse.sumomo.pos_doc_backend.persistence.repository.IngestionJobRepository;

/**
 * Read service for {@code GET /api/v1/ingestion-jobs/{jobId}}.
 *
 * <p>Preserves the existing behavior of the controller: it reads the persisted
 * job whose POS record is not soft-deleted and maps it to the DTO inside a
 * read-only transaction, returning the existing sanitized
 * {@code INGESTION_JOB_NOT_FOUND} problem when absent.
 */
@Service
public class IngestionJobReadService {

	private final IngestionJobRepository ingestionJobRepository;

	public IngestionJobReadService(IngestionJobRepository ingestionJobRepository) {
		this.ingestionJobRepository = Objects.requireNonNull(ingestionJobRepository);
	}

	@Transactional(readOnly = true)
	public IngestionJob getJob(UUID jobId) {
		IngestionJobEntity entity = this.ingestionJobRepository.findByIdAndPosRecordDeletedAtIsNull(jobId)
				.orElseThrow(() -> new IntakeException(IntakeException.Code.INGESTION_JOB_NOT_FOUND));
		return IngestionJobApiMapper.toDto(entity);
	}

}
