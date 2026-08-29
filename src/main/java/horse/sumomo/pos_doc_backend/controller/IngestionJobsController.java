package horse.sumomo.pos_doc_backend.controller;

import com.yourcompany.pos.api.IngestionJobsApi;
import com.yourcompany.pos.api.model.IngestionJob;
import horse.sumomo.pos_doc_backend.service.DummyIngestionJobService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * HTTP skeleton for the ingestion-job endpoint. Returns deterministic dummy
 * data only; no queue, persistence, or processing is performed.
 */
@RestController
public class IngestionJobsController implements IngestionJobsApi {

    private final DummyIngestionJobService ingestionJobService;

    public IngestionJobsController(DummyIngestionJobService ingestionJobService) {
        this.ingestionJobService = ingestionJobService;
    }

    @Override
    public ResponseEntity<IngestionJob> getIngestionJob(UUID jobId) {
        return ResponseEntity.ok(ingestionJobService.ingestionJob(jobId));
    }
}
