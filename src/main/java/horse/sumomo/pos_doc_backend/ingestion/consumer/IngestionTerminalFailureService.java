package horse.sumomo.pos_doc_backend.ingestion.consumer;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import horse.sumomo.pos_doc_backend.persistence.entity.IngestionJobEntity;
import horse.sumomo.pos_doc_backend.persistence.entity.PosRecordEntity;
import horse.sumomo.pos_doc_backend.persistence.model.PosRecordStatus;
import horse.sumomo.pos_doc_backend.persistence.repository.IngestionJobRepository;
import horse.sumomo.pos_doc_backend.persistence.repository.PosRecordRepository;

/**
 * Marks an ingestion job (and its active POS record) as {@code FAILED} in a
 * fresh short transaction. Called by the AMQP recoverer once retries are
 * exhausted, and by the listener when a non-retryable failure is raised.
 *
 * <p>This service is intentionally separate from
 * {@link IngestionConsumerService}: it must run in its own
 * {@link Propagation#REQUIRES_NEW} transaction so a database failure on
 * the consumer's main flow cannot prevent the terminal {@code FAILED}
 * state from being recorded, and so the listener can ack/reject the
 * message after the FAILED transition is durable.
 */
@Service
public class IngestionTerminalFailureService {

	private static final Logger log = LoggerFactory.getLogger(IngestionTerminalFailureService.class);

	private final IngestionTerminalFailureService self;
	private final IngestionJobRepository jobRepository;
	private final PosRecordRepository recordRepository;

	public IngestionTerminalFailureService(@Lazy IngestionTerminalFailureService self,
			IngestionJobRepository jobRepository, PosRecordRepository recordRepository) {
		this.self = self;
		this.jobRepository = Objects.requireNonNull(jobRepository);
		this.recordRepository = Objects.requireNonNull(recordRepository);
	}

	/**
	 * Marks the job and its active POS record as failed. No-op if the
	 * job is already in a terminal state, or if it has been deleted.
	 */
	public void markTerminal(IngestionConsumerService.IngestionMessageIdentifiers ids,
			ConsumerException cause) {
		Objects.requireNonNull(ids, "ids must not be null");
		Objects.requireNonNull(cause, "cause must not be null");
		try {
			self.markTerminalInNewTx(ids, cause);
		}
		catch (RuntimeException e) {
			// The DLQ outcome must not depend on this side effect.
			log.warn("Terminal-state recording failed (category=terminal-record-failed); jobId={}, code={}",
					ids.jobId(), cause.getCode().code());
		}
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	protected void markTerminalInNewTx(IngestionConsumerService.IngestionMessageIdentifiers ids,
			ConsumerException cause) {
		UUID jobId = ids.jobId();
		Instant now = Instant.now();
		IngestionJobEntity job = this.jobRepository.findById(jobId).orElse(null);
		if (job == null) {
			return;
		}
		// Idempotent: a job that has already been marked FAILED stays
		// FAILED — never overwrite a terminal state with a new error
		// code (the original reason is more informative).
		if (job.getStatus() == horse.sumomo.pos_doc_backend.persistence.model.JobStatus.FAILED
				|| job.getStatus() == horse.sumomo.pos_doc_backend.persistence.model.JobStatus.COMPLETED) {
			return;
		}
		String code = cause.getCode().code();
		String safeMessage = cause.getCode().detail();
		job.fail(now, code, safeMessage);
		this.jobRepository.saveAndFlush(job);

		// Roll the POS record forward only if it is still in
		// PROCESSING — the upload step put it there; if a later admin
		// action set it to a different state, leave it alone.
		PosRecordEntity record = job.getPosRecord();
		if (record != null && record.getDeletedAt() == null
				&& record.getStatus() == PosRecordStatus.PROCESSING) {
			record.setStatus(PosRecordStatus.FAILED);
			record.setUpdatedAt(now);
			this.recordRepository.saveAndFlush(record);
		}
		log.warn("Job permanently failed (category={}); jobId={}", code, jobId);
	}
}