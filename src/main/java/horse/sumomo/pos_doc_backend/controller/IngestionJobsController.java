package horse.sumomo.pos_doc_backend.controller;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.yourcompany.pos.api.IngestionJobsApi;
import com.yourcompany.pos.api.model.IngestionJob;

import horse.sumomo.pos_doc_backend.ingestion.application.IntakeException;
import horse.sumomo.pos_doc_backend.ingestion.mapping.IngestionJobApiMapper;
import horse.sumomo.pos_doc_backend.persistence.entity.IngestionJobEntity;
import horse.sumomo.pos_doc_backend.persistence.repository.IngestionJobRepository;

/**
 * Ingestion-job endpoint. Returns the persisted job so a client can observe
 * its {@code QUEUED} state after an accepted upload.
 */
@RestController
public class IngestionJobsController implements IngestionJobsApi {

	private final IngestionJobRepository ingestionJobRepository;

	public IngestionJobsController(IngestionJobRepository ingestionJobRepository) {
		this.ingestionJobRepository = ingestionJobRepository;
	}

	@Override
	public ResponseEntity<IngestionJob> getIngestionJob(UUID jobId) {
		// The active-record projection excludes jobs whose POS record is
		// soft-deleted, matching the "does not exist" contract.
		IngestionJobEntity entity = this.ingestionJobRepository.findByIdAndPosRecordDeletedAtIsNull(jobId)
				.orElseThrow(() -> new IntakeException(IntakeException.Code.INGESTION_JOB_NOT_FOUND));
		return ResponseEntity.ok(IngestionJobApiMapper.toDto(entity));
	}

}
