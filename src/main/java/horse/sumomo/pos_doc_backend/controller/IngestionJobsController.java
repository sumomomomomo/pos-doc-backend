package horse.sumomo.pos_doc_backend.controller;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.yourcompany.pos.api.IngestionJobsApi;
import com.yourcompany.pos.api.model.IngestionJob;

import horse.sumomo.pos_doc_backend.review.IngestionJobReadService;

/**
 * Ingestion-job endpoint. Delegates to {@link IngestionJobReadService} so the
 * repository access lives behind a read service; behavior (persisted job,
 * sanitized 404) is unchanged.
 */
@RestController
public class IngestionJobsController implements IngestionJobsApi {

	private final IngestionJobReadService ingestionJobReadService;

	public IngestionJobsController(IngestionJobReadService ingestionJobReadService) {
		this.ingestionJobReadService = ingestionJobReadService;
	}

	@Override
	public ResponseEntity<IngestionJob> getIngestionJob(UUID jobId) {
		return ResponseEntity.ok(this.ingestionJobReadService.getJob(jobId));
	}

}
