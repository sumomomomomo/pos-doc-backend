package horse.sumomo.pos_doc_backend.ingestion.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import horse.sumomo.pos_doc_backend.persistence.entity.IngestionJobEntity;
import horse.sumomo.pos_doc_backend.persistence.entity.OutboxEventEntity;
import horse.sumomo.pos_doc_backend.persistence.entity.PosRecordEntity;
import horse.sumomo.pos_doc_backend.persistence.entity.StorageObjectEntity;
import horse.sumomo.pos_doc_backend.persistence.model.JobStatus;
import horse.sumomo.pos_doc_backend.persistence.model.PosRecordStatus;
import horse.sumomo.pos_doc_backend.persistence.repository.IngestionJobRepository;
import horse.sumomo.pos_doc_backend.persistence.repository.OutboxEventRepository;
import horse.sumomo.pos_doc_backend.persistence.repository.PosRecordRepository;
import horse.sumomo.pos_doc_backend.persistence.repository.StorageObjectRepository;

/**
 * Persists one accepted upload as four rows in a single SQLite transaction.
 *
 * <p>The {@link #persist(UploadCommand)} method is the only transaction
 * boundary: storage object, POS record, ingestion job, and outbox event are
 * created and flushed together, so a uniqueness violation rolls back all
 * four rows. Advisory {@code exists} pre-checks produce clearer 409 codes
 * for the common case; the database unique indexes remain the final
 * authority and are classified from the stable column identifier only.
 */
@Service
public class IntakeDatabaseService {

	private static final Logger log = LoggerFactory.getLogger(IntakeDatabaseService.class);

	private final StorageObjectRepository storageObjectRepository;
	private final PosRecordRepository posRecordRepository;
	private final IngestionJobRepository ingestionJobRepository;
	private final OutboxEventRepository outboxEventRepository;

	public IntakeDatabaseService(StorageObjectRepository storageObjectRepository,
			PosRecordRepository posRecordRepository, IngestionJobRepository ingestionJobRepository,
			OutboxEventRepository outboxEventRepository) {
		this.storageObjectRepository = storageObjectRepository;
		this.posRecordRepository = posRecordRepository;
		this.ingestionJobRepository = ingestionJobRepository;
		this.outboxEventRepository = outboxEventRepository;
	}

	/**
	 * Commits the four intake rows atomically.
	 *
	 * @throws IntakeException with {@link IntakeException.Code#DUPLICATE_EREF_NUMBER}
	 *             or {@link IntakeException.Code#DUPLICATE_POLICY_NUMBER} on a
	 *             uniqueness conflict
	 */
	@Transactional
	public void persist(UploadCommand command) {
		// Advisory pre-checks for clear error codes; the unique indexes are
		// the final authority and are classified below.
		String normalizedEref = command.displayEref() == null ? null
				: horse.sumomo.pos_doc_backend.persistence.normalization.MetadataNormalizer
						.normalizeIdentifier(command.displayEref());
		if (normalizedEref != null
				&& this.posRecordRepository.existsByErefNumberNormalizedAndDeletedAtIsNull(normalizedEref)) {
			throw new IntakeException(IntakeException.Code.DUPLICATE_EREF_NUMBER);
		}
		String normalizedPolicy = command.displayPolicyNumber() == null ? null
				: horse.sumomo.pos_doc_backend.persistence.normalization.MetadataNormalizer
						.normalizeIdentifier(command.displayPolicyNumber());
		if (normalizedPolicy != null
				&& this.posRecordRepository.existsByPolicyNumberNormalizedAndDeletedAtIsNull(normalizedPolicy)) {
			throw new IntakeException(IntakeException.Code.DUPLICATE_POLICY_NUMBER);
		}

		try {
			StorageObjectEntity storage = new StorageObjectEntity(command.storageObjectId(), command.objectKey(),
					command.safeFilename(), "application/zip", command.compressedBytes(), command.sha256(),
					command.requestedAt());
			this.storageObjectRepository.saveAndFlush(storage);

			PosRecordEntity record = new PosRecordEntity(command.posRecordId(), storage, PosRecordStatus.UPLOADED,
					command.uploaderSubject(), command.requestedAt(), command.requestedAt());
			record.setErefNumber(command.displayEref());
			if (command.displayPolicyNumber() != null) {
				record.setPolicyNumber(command.displayPolicyNumber());
			}
			this.posRecordRepository.saveAndFlush(record);

			IngestionJobEntity job = new IngestionJobEntity(command.jobId(), record, JobStatus.QUEUED, 0L,
					command.requestedAt());
			this.ingestionJobRepository.saveAndFlush(job);

			OutboxEventEntity event = new OutboxEventEntity(command.outboxEventId(), command.jobId(),
					command.payloadJson(), command.requestedAt());
			this.outboxEventRepository.saveAndFlush(event);

			// Force remaining inserts so any constraint violation occurs
			// inside this method and rolls back the whole transaction.
			this.outboxEventRepository.flush();
		}
		catch (DataIntegrityViolationException e) {
			throw classifyConflict(e);
		}
	}

	/**
	 * Maps a uniqueness conflict to the specific duplicate code. Only the
	 * stable index/column identifier is inspected from the exception chain;
	 * no raw exception text is propagated to the client.
	 */
	private IntakeException classifyConflict(DataIntegrityViolationException e) {
		String chain = exceptionChainText(e);
		if (chain != null && chain.contains("uq_pos_record_active_policy")) {
			return new IntakeException(IntakeException.Code.DUPLICATE_POLICY_NUMBER, e);
		}
		if (chain != null && (chain.contains("uq_pos_record_active_eref")
				|| chain.contains("eref_number_normalized"))) {
			return new IntakeException(IntakeException.Code.DUPLICATE_EREF_NUMBER, e);
		}
		log.warn("Intake persistence failed with an integrity violation (category=integrity-violation)");
		return new IntakeException(IntakeException.Code.INGESTION_INTAKE_FAILED, e);
	}

	private static String exceptionChainText(Throwable t) {
		StringBuilder sb = new StringBuilder();
		Throwable current = t;
		int depth = 0;
		while (current != null && depth < 10) {
			if (current.getMessage() != null) {
				sb.append(current.getMessage()).append(' ');
			}
			current = current.getCause();
			depth = depth + 1;
		}
		return sb.length() == 0 ? null : sb.toString();
	}

}
