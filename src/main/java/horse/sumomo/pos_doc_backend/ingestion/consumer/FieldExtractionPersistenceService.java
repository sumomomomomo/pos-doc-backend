package horse.sumomo.pos_doc_backend.ingestion.consumer;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import horse.sumomo.pos_doc_backend.ingestion.application.DocumentSnapshot;
import horse.sumomo.pos_doc_backend.persistence.entity.IngestionJobEntity;
import horse.sumomo.pos_doc_backend.persistence.entity.PosDocumentEntity;
import horse.sumomo.pos_doc_backend.persistence.entity.PosFieldExtractionEntity;
import horse.sumomo.pos_doc_backend.persistence.entity.PosRecordEntity;
import horse.sumomo.pos_doc_backend.persistence.model.DocumentProcessingStatus;
import horse.sumomo.pos_doc_backend.persistence.model.ExtractionField;
import horse.sumomo.pos_doc_backend.persistence.model.PosRecordStatus;
import horse.sumomo.pos_doc_backend.persistence.repository.IngestionJobRepository;
import horse.sumomo.pos_doc_backend.persistence.repository.PosDocumentRepository;
import horse.sumomo.pos_doc_backend.persistence.repository.PosFieldExtractionRepository;
import horse.sumomo.pos_doc_backend.persistence.repository.PosRecordRepository;

/**
 * Persistence and completion boundary for the structured field-extraction
 * workflow.
 *
 * <p>Each public method is its own short transaction (matching the existing
 * consumer style): document snapshots, business-field resolution, candidate
 * status transitions, idempotent outcome upserts, null-guarded optimistic
 * business-field updates, and the completion gate. There is no long transaction
 * surrounding model calls.
 *
 * <p>The completion gate verifies, in one transaction: the job belongs to the
 * record, the record is active (in-flight and not soft-deleted), at least one
 * PDF exists, and every document is terminal (COMPLETED/FAILED/SKIPPED). Null
 * business fields are allowed. Invariant "each processed field/candidate
 * decision is durably represented" is maintained by the workflow's synchronous
 * per-field durability (every model call is followed by a durable outcome
 * before the candidate is marked COMPLETED); this gate catches the crash case
 * where a document was left non-terminal.
 */
@Service
public class FieldExtractionPersistenceService {

	private static final Logger log = LoggerFactory.getLogger(FieldExtractionPersistenceService.class);

	private final PosDocumentRepository documentRepository;
	private final PosRecordRepository recordRepository;
	private final IngestionJobRepository jobRepository;
	private final PosFieldExtractionRepository fieldExtractionRepository;
	private final JdbcTemplate jdbcTemplate;

	public FieldExtractionPersistenceService(PosDocumentRepository documentRepository,
			PosRecordRepository recordRepository, IngestionJobRepository jobRepository,
			PosFieldExtractionRepository fieldExtractionRepository, JdbcTemplate jdbcTemplate) {
		this.documentRepository = documentRepository;
		this.recordRepository = recordRepository;
		this.jobRepository = jobRepository;
		this.fieldExtractionRepository = fieldExtractionRepository;
		this.jdbcTemplate = jdbcTemplate;
	}

	/**
	 * Loads the record's PDF documents in deterministic ZIP entry/sequence order as
	 * {@link DocumentSnapshot} DTOs (id, sequence, stored filename), computed inside
	 * this read-only transaction so the lazy storage object is initialized here.
	 * The returned DTOs carry no lazy associations, so the caller may use them
	 * outside any transaction.
	 */
	@Transactional(readOnly = true)
	public List<DocumentSnapshot> snapshotPosDocuments(UUID posRecordId) {
		return this.documentRepository.findByPosRecordIdOrderBySequenceNumberAsc(posRecordId).stream()
				.filter(d -> d.getStorageObject() != null
							&& "application/pdf".equalsIgnoreCase(d.getStorageObject().getContentType()))
				.map(d -> new DocumentSnapshot(d.getId(), (int) d.getSequenceNumber(),
						d.getStorageObject().getOriginalFilename()))
				.toList();
	}

	/**
	 * Returns the set of the three business fields that currently have a
	 * non-null value on the record (read-only transaction). Fields not in the
	 * set are the unresolved fields still eligible for extraction.
	 */
	@Transactional(readOnly = true)
	public Set<ExtractionField> resolvedBusinessFields(UUID posRecordId) {
		PosRecordEntity record = this.activeRecord(posRecordId);
		Set<ExtractionField> resolved = EnumSet.noneOf(ExtractionField.class);
		if (record.getPolicyholderName() != null) {
			resolved.add(ExtractionField.POLICYHOLDER_NAME);
		}
		if (record.getConsultantName() != null) {
			resolved.add(ExtractionField.CONSULTANT_NAME);
		}
		if (record.getPolicyCreateDate() != null) {
			resolved.add(ExtractionField.POLICY_CREATE_DATE);
		}
		return resolved;
	}

	/**
	 * Returns the durable outcome for (document, field, prompt version), if any
	 * (read-only transaction).
	 */
	@Transactional(readOnly = true)
	public Optional<PosFieldExtractionEntity> loadOutcome(UUID documentId, ExtractionField field,
			int promptVersion) {
		return this.fieldExtractionRepository
				.findByDocumentIdAndFieldNameAndPromptVersion(documentId, field.name(), promptVersion);
	}

	/**
	 * Marks the supplied non-candidate documents {@code SKIPPED} (one
	 * transaction). A document already terminal is never demoted.
	 */
	@Transactional
	public void markDocumentsSkipped(Collection<UUID> documentIds) {
		for (UUID id : documentIds) {
			PosDocumentEntity document = this.requireDocument(id);
			DocumentProcessingStatus current = document.getProcessingStatus();
			if (current != DocumentProcessingStatus.COMPLETED && current != DocumentProcessingStatus.FAILED
					&& current != DocumentProcessingStatus.SKIPPED) {
				document.setProcessingStatus(DocumentProcessingStatus.SKIPPED);
				this.documentRepository.saveAndFlush(document);
			}
		}
	}

	/**
	 * Marks the candidate document {@code PROCESSING} (one transaction).
	 */
	@Transactional
	public void markDocumentProcessing(UUID documentId) {
		PosDocumentEntity document = this.requireDocument(documentId);
		document.setProcessingStatus(DocumentProcessingStatus.PROCESSING);
		this.documentRepository.saveAndFlush(document);
	}

	/**
	 * Marks the candidate document {@code COMPLETED} (one transaction).
	 */
	@Transactional
	public void markDocumentCompleted(UUID documentId) {
		PosDocumentEntity document = this.requireDocument(documentId);
		document.setProcessingStatus(DocumentProcessingStatus.COMPLETED);
		this.documentRepository.saveAndFlush(document);
	}

	/**
	 * Idempotently upserts a durable outcome row:
	 * {@code INSERT ... ON CONFLICT(document_id, field_name, prompt_version) DO
	 * NOTHING}, then re-reads. A pre-existing row is authoritative and returned;
	 * the supplied row is ignored when it already exists. One transaction.
	 *
	 * @return the durable row (either the newly inserted one or the pre-existing one)
	 */
	@Transactional
	public PosFieldExtractionEntity upsertExtractionOutcome(PosFieldExtractionEntity entity) {
		var id = entity.getId();
		int affected = this.jdbcTemplate.update(
				"INSERT INTO pos_field_extraction (document_id, field_name, prompt_version, outcome, "
						+ "value_text, model, finish_reason, attempt_count, error_code, completed_at_epoch_ms) "
						+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
						+ "ON CONFLICT(document_id, field_name, prompt_version) DO NOTHING",
				id.getDocumentId().toString(), id.getFieldName(), id.getPromptVersion(),
				entity.getOutcome().name(), entity.getValueText(), entity.getModel(), entity.getFinishReason(),
				entity.getAttemptCount(), entity.getErrorCode(), entity.getCompletedAt().toEpochMilli());
		if (affected > 0) {
			return entity;
		}
		return this.fieldExtractionRepository
				.findByDocumentIdAndFieldNameAndPromptVersion(id.getDocumentId(), id.getFieldName(),
						id.getPromptVersion())
				.orElseThrow(() -> new ConsumerException(ConsumerException.Code.ID_MISMATCH));
	}

	/**
	 * Applies a resolved canonical value to the record's business field, updating
	 * only when the field is currently null (one transaction). Uses the entity
	 * setters (which normalize names) and the JPA {@code @Version} for optimistic
	 * locking. A null field is left unchanged when a value is already present.
	 */
	@Transactional
	public void applyResolvedField(UUID posRecordId, ExtractionField field, String canonicalValue) {
		PosRecordEntity record = this.activeRecord(posRecordId);
		switch (field) {
			case POLICYHOLDER_NAME -> {
				if (record.getPolicyholderName() == null) {
					record.setPolicyholderName(canonicalValue);
					this.recordRepository.saveAndFlush(record);
				}
			}
			case CONSULTANT_NAME -> {
				if (record.getConsultantName() == null) {
					record.setConsultantName(canonicalValue);
					this.recordRepository.saveAndFlush(record);
				}
			}
			case POLICY_CREATE_DATE -> {
				if (record.getPolicyCreateDate() == null) {
					record.setPolicyCreateDate(LocalDate.parse(canonicalValue));
					this.recordRepository.saveAndFlush(record);
				}
			}
		}
	}

	/**
	 * Completes the extraction workflow and moves the record to
	 * {@code REVIEW_REQUIRED}, after verifying the completion invariants in one
	 * transaction:
	 * <ol>
	 *   <li>the job belongs to the supplied POS record;</li>
	 *   <li>the record is active (in-flight {@code PROCESSING} and not soft-deleted);</li>
	 *   <li>at least one PDF exists;</li>
	 *   <li>every document is terminal (COMPLETED, FAILED, or SKIPPED); and</li>
	 *   <li>null business fields are allowed (no constraint on them).</li>
	 * </ol>
	 *
	 * <p>Throws {@link ConsumerException} if any invariant fails, so the job is
	 * never completed in a partially-verified state.
	 */
	@Transactional
	public void completeWorkflow(UUID jobId, UUID posRecordId, Instant now) {
		// 1. The job belongs to the supplied POS record.
		IngestionJobEntity job = this.jobRepository.findById(jobId)
				.orElseThrow(() -> new ConsumerException(ConsumerException.Code.ID_MISMATCH));
		if (job.getPosRecord() == null || !job.getPosRecord().getId().equals(posRecordId)) {
			throw new ConsumerException(ConsumerException.Code.ID_MISMATCH);
		}

		// 2. The record is active (in-flight and not soft-deleted).
		PosRecordEntity record = this.recordRepository.findByIdAndDeletedAtIsNull(posRecordId)
				.orElseThrow(() -> new ConsumerException(ConsumerException.Code.RECORD_DELETED));
		if (record.getStatus() != PosRecordStatus.PROCESSING) {
			throw new ConsumerException(ConsumerException.Code.EXTRACTION_STATE_CONFLICT,
					"Record " + posRecordId + " is not active for completion (status="
							+ record.getStatus() + ")");
		}

		// 3. At least one PDF exists.
		List<PosDocumentEntity> documents = this.documentRepository
				.findByPosRecordIdOrderBySequenceNumberAsc(posRecordId);
		long pdfCount = documents.stream()
				.filter(d -> isPdf(d.getStorageObject())).count();
		if (pdfCount == 0) {
			throw new ConsumerException(ConsumerException.Code.EXTRACTION_STATE_CONFLICT,
					"No PDF documents found for POS record " + posRecordId);
		}

		// 4. Every document is terminal (COMPLETED, FAILED, or SKIPPED).
		for (PosDocumentEntity document : documents) {
			DocumentProcessingStatus status = document.getProcessingStatus();
			if (status != DocumentProcessingStatus.COMPLETED && status != DocumentProcessingStatus.FAILED
					&& status != DocumentProcessingStatus.SKIPPED) {
				throw new ConsumerException(ConsumerException.Code.EXTRACTION_STATE_CONFLICT,
						"Document " + document.getId() + " is not terminal (status=" + status + ")");
			}
		}

		// 5. All checks passed: complete the job and move the record.
		job.complete(now);
		job.setErrorCode(null);
		job.setErrorMessage(null);
		this.jobRepository.saveAndFlush(job);

		record.setStatus(PosRecordStatus.REVIEW_REQUIRED);
		record.setUpdatedAt(now);
		this.recordRepository.saveAndFlush(record);

		log.info("Field-extraction workflow completed and record moved to REVIEW_REQUIRED "
				+ "(category=field-extraction-completion); jobId={}", jobId);
	}

	private PosRecordEntity activeRecord(UUID posRecordId) {
		return this.recordRepository.findByIdAndDeletedAtIsNull(posRecordId)
				.orElseThrow(() -> new ConsumerException(ConsumerException.Code.RECORD_DELETED));
	}

	private PosDocumentEntity requireDocument(UUID documentId) {
		return this.documentRepository.findById(documentId)
				.orElseThrow(() -> new ConsumerException(ConsumerException.Code.ID_MISMATCH));
	}

	private static boolean isPdf(horse.sumomo.pos_doc_backend.persistence.entity.StorageObjectEntity storage) {
		return storage != null && "application/pdf".equalsIgnoreCase(storage.getContentType());
	}

}
