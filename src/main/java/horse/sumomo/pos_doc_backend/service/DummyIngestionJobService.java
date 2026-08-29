package horse.sumomo.pos_doc_backend.service;

import com.yourcompany.pos.api.model.IngestionJob;
import com.yourcompany.pos.api.model.JobStatus;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Deterministic dummy data for the ingestion-job endpoint. Skeleton only: no
 * queue, persistence, or processing is performed.
 */
@Service
public class DummyIngestionJobService {

    public static final UUID INGESTION_JOB_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");

    private static final OffsetDateTime FIXED_TIMESTAMP = OffsetDateTime.parse("2026-01-01T00:00:00Z");

    /**
     * Complete dummy ingestion job whose ID equals the supplied path job ID
     * and whose POS record ID equals the fixed POS record ID.
     */
    public IngestionJob ingestionJob(UUID jobId) {
        return new IngestionJob(jobId, DummyPosRecordService.POS_RECORD_ID, JobStatus.COMPLETED, 1,
                FIXED_TIMESTAMP)
                .startedAt(FIXED_TIMESTAMP)
                .completedAt(FIXED_TIMESTAMP);
    }
}
