package horse.sumomo.pos_doc_backend.ingestion.consumer;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import horse.sumomo.pos_doc_backend.persistence.entity.DocumentOcrResultEntity;
import horse.sumomo.pos_doc_backend.persistence.entity.DocumentOcrResultId;
import horse.sumomo.pos_doc_backend.persistence.entity.IngestionJobEntity;
import horse.sumomo.pos_doc_backend.persistence.entity.PosDocumentEntity;
import horse.sumomo.pos_doc_backend.persistence.entity.PosRecordEntity;
import horse.sumomo.pos_doc_backend.persistence.model.DocumentProcessingStatus;
import horse.sumomo.pos_doc_backend.persistence.model.PosRecordStatus;
import horse.sumomo.pos_doc_backend.persistence.repository.DocumentOcrResultRepository;
import horse.sumomo.pos_doc_backend.persistence.repository.IngestionJobRepository;
import horse.sumomo.pos_doc_backend.persistence.repository.PosDocumentRepository;
import horse.sumomo.pos_doc_backend.persistence.repository.PosRecordRepository;

/**
 * Transaction-bound persistence service for document OCR state.
 *
 * <p>Each public method runs in its own short SQLite transaction. The
 * workflow service calls these methods from outside any transaction so
 * that MinIO access, PDF rendering, and HTTP calls to llama.cpp never
 * occur while a database transaction is open.
 *
 * <p>The service never logs or includes OCR text in any exception
 * message.
 */
@Service
public class DocumentOcrPersistenceService {

	private static final Logger log = LoggerFactory.getLogger(DocumentOcrPersistenceService.class);

	private final DocumentOcrResultRepository ocrResultRepository;
	private final PosDocumentRepository documentRepository;
	private final PosRecordRepository recordRepository;
	private final IngestionJobRepository jobRepository;

	public DocumentOcrPersistenceService(DocumentOcrResultRepository ocrResultRepository,
			PosDocumentRepository documentRepository, PosRecordRepository recordRepository,
			IngestionJobRepository jobRepository) {
		this.ocrResultRepository = Objects.requireNonNull(ocrResultRepository);
		this.documentRepository = Objects.requireNonNull(documentRepository);
		this.recordRepository = Objects.requireNonNull(recordRepository);
		this.jobRepository = Objects.requireNonNull(jobRepository);
	}

	/**
	 * Result of inspecting a document's OCR state.
	 */
	public enum DocumentOcrState {
		/** A version-1 result already exists; skip OCR. */
		ALREADY_COMPLETED,
		/** The document was marked PROCESSING; proceed to OCR. */
		READY_FOR_OCR,
		/** The document is already COMPLETED (status repaired); skip OCR. */
		STATUS_REPAIRED_COMPLETED
	}

	/**
	 * Inspects the document and version-1 OCR-result state in a short
	 * transaction. If a version-1 result already exists, makes the
	 * document status consistent with completion and returns
	 * {@link DocumentOcrState#ALREADY_COMPLETED}. Otherwise changes
	 * {@code PENDING} or stale {@code PROCESSING} to {@code PROCESSING}
	 * and returns {@link DocumentOcrState#READY_FOR_OCR}.
	 *
	 * <p>If the document is {@code COMPLETED} but missing its version-1
	 * result, the status is repaired to {@code PENDING} and the document
	 * is processed (returns {@code READY_FOR_OCR}).
	 *
	 * <p>If a version-1 result exists but the document status is not
	 * {@code COMPLETED}, the status is repaired to {@code COMPLETED} and
	 * {@link DocumentOcrState#STATUS_REPAIRED_COMPLETED} is returned.
	 */
	@Transactional
	public DocumentOcrState inspectAndPrepare(UUID documentId, int promptVersion) {
		PosDocumentEntity document = this.documentRepository.findById(documentId)
				.orElseThrow(() -> new ConsumerException(ConsumerException.Code.ID_MISMATCH));

		Optional<DocumentOcrResultEntity> existing = this.ocrResultRepository
				.findByDocumentIdAndPromptVersion(documentId, promptVersion);

		if (existing.isPresent()) {
			// A version-1 result exists: authoritative durable evidence.
			// Repair the status to COMPLETED if it is not already.
			if (document.getProcessingStatus() != DocumentProcessingStatus.COMPLETED) {
				document.setProcessingStatus(DocumentProcessingStatus.COMPLETED);
				this.documentRepository.saveAndFlush(document);
				log.debug("Repaired document status to COMPLETED (category=ocr-status-repair); documentId={}",
						documentId);
			}
			return DocumentOcrState.ALREADY_COMPLETED;
		}

		// No version-1 result. Handle the inconsistent case: document is
		// COMPLETED but missing its result — repair to PENDING and process.
		if (document.getProcessingStatus() == DocumentProcessingStatus.COMPLETED) {
			document.setProcessingStatus(DocumentProcessingStatus.PENDING);
			this.documentRepository.saveAndFlush(document);
			log.debug("Repaired inconsistent COMPLETED document to PENDING (category=ocr-status-repair); documentId={}",
					documentId);
		}

		// PENDING or stale PROCESSING: transition to PROCESSING.
		document.setProcessingStatus(DocumentProcessingStatus.PROCESSING);
		this.documentRepository.saveAndFlush(document);
		return DocumentOcrState.READY_FOR_OCR;
	}

	/**
	 * Persists the OCR result and marks the document {@code COMPLETED} in
	 * a new short transaction. If another result for the same key already
	 * exists, compares the OCR text and all metadata for exact equality.
	 * An equivalent already-committed result is treated as idempotent
	 * success. A differing result raises a stable internal consistency
	 * failure without placing either text in an exception or log.
	 *
	 * <p>Handles the concurrent-insert race: if a uniqueness constraint
	 * violation occurs on insert (another transaction committed first),
	 * the method re-reads the committed row and reconciles it into either
	 * idempotent success or a stable conflict, rather than leaking a
	 * raw database exception.
	 */
	@Transactional
	public void persistOcrResult(UUID documentId, int promptVersion, String ocrText, String model,
			String finishReason, Instant completedAt) {
		// Check for an existing result first (idempotency).
		Optional<DocumentOcrResultEntity> existing = this.ocrResultRepository
				.findByDocumentIdAndPromptVersion(documentId, promptVersion);
		if (existing.isPresent()) {
			reconcileExistingResult(existing.get(), ocrText, model, finishReason, documentId, promptVersion);
			return;
		}

		DocumentOcrResultId id = new DocumentOcrResultId(documentId, promptVersion);
		DocumentOcrResultEntity result = new DocumentOcrResultEntity(id, ocrText, model, finishReason, completedAt);
		try {
			this.ocrResultRepository.saveAndFlush(result);
		}
		catch (org.springframework.dao.DataIntegrityViolationException | org.springframework.jdbc.UncategorizedSQLException e) {
			// Concurrent-insert race: another transaction committed a row
			// for the same (document_id, prompt_version) between our
			// check and our insert. Re-read and reconcile.
			Optional<DocumentOcrResultEntity> committed = this.ocrResultRepository
					.findByDocumentIdAndPromptVersion(documentId, promptVersion);
			if (committed.isPresent()) {
				reconcileExistingResult(committed.get(), ocrText, model, finishReason, documentId, promptVersion);
				return;
			}
			throw e;
		}

		PosDocumentEntity document = this.documentRepository.findById(documentId)
				.orElseThrow(() -> new ConsumerException(ConsumerException.Code.ID_MISMATCH));
		document.setProcessingStatus(DocumentProcessingStatus.COMPLETED);
		this.documentRepository.saveAndFlush(document);

		log.debug("OCR result persisted (category=ocr-persist-success); documentId={}, promptVersion={}",
				documentId, promptVersion);
	}

	/**
	 * Reconciles an already-committed OCR result against the proposed
	 * values. Equivalent means the OCR text and all metadata are exactly
	 * equal. The OCR text is compared internally using
	 * {@link String#equals} but is never logged or included in an
	 * exception message.
	 */
	private void reconcileExistingResult(DocumentOcrResultEntity existing, String ocrText, String model,
			String finishReason, UUID documentId, int promptVersion) {
		boolean equivalent = existing.getOcrText().equals(ocrText)
				&& existing.getModel().equals(model)
				&& existing.getFinishReason().equals(finishReason);
		if (equivalent) {
			// Equivalent already-committed result: idempotent success.
			// Ensure the document is COMPLETED.
			PosDocumentEntity document = this.documentRepository.findById(documentId)
					.orElseThrow(() -> new ConsumerException(ConsumerException.Code.ID_MISMATCH));
			if (document.getProcessingStatus() != DocumentProcessingStatus.COMPLETED) {
				document.setProcessingStatus(DocumentProcessingStatus.COMPLETED);
				this.documentRepository.saveAndFlush(document);
			}
			log.debug("OCR result already committed (category=ocr-idempotent); documentId={}", documentId);
			return;
		}
		// Differing result: stable internal consistency failure.
		// Never include OCR text in the exception.
		throw new ConsumerException(ConsumerException.Code.EXTRACTION_STATE_CONFLICT,
				"OCR result conflict for document " + documentId + " prompt version " + promptVersion);
	}

	/**
	 * Returns the current document status for a document ID.
	 */
	@Transactional(readOnly = true)
	public DocumentProcessingStatus getDocumentStatus(UUID documentId) {
		return this.documentRepository.findById(documentId)
				.map(PosDocumentEntity::getProcessingStatus)
				.orElseThrow(() -> new ConsumerException(ConsumerException.Code.ID_MISMATCH));
	}

	/**
	 * Loads all documents for a POS record in deterministic sequence
	 * order, with document UUID as a tie-breaker.
	 */
	@Transactional(readOnly = true)
	public List<PosDocumentEntity> loadDocumentsInSequenceOrder(UUID posRecordId) {
		return this.documentRepository.findByPosRecordIdOrderBySequenceNumberAsc(posRecordId);
	}

	/**
	 * Atomically verifies that every document for the POS record is
	 * {@code COMPLETED} with exactly one version-1 OCR result, and only
	 * then completes the job and moves the record to
	 * {@code REVIEW_REQUIRED} — all in a single transaction.
	 *
	 * <p>Inside this single transaction:
	 * <ol>
	 *   <li>Verify the job belongs to the supplied POS record.</li>
	 *   <li>Verify the record is active (not soft-deleted).</li>
	 *   <li>Verify at least one document exists.</li>
	 *   <li>Verify every document is {@code COMPLETED}.</li>
	 *   <li>Verify exactly one version-1 result exists per document.</li>
	 *   <li>Complete the job and move the record to
	 *       {@code REVIEW_REQUIRED}.</li>
	 * </ol>
	 *
	 * <p>Throws {@link ConsumerException} when any verification fails,
	 * so the job is never completed in a partially-verified state.
	 */
	@Transactional
	public void completeIfAllDocumentsOcrComplete(UUID jobId, UUID posRecordId, int promptVersion,
			Instant now) {
		// 1. Verify the job belongs to the supplied POS record.
		IngestionJobEntity job = this.jobRepository.findById(jobId)
				.orElseThrow(() -> new ConsumerException(ConsumerException.Code.ID_MISMATCH));
		if (job.getPosRecord() == null || !job.getPosRecord().getId().equals(posRecordId)) {
			throw new ConsumerException(ConsumerException.Code.ID_MISMATCH);
		}

		// 2. Verify the record is active.
		PosRecordEntity record = this.recordRepository.findByIdAndDeletedAtIsNull(posRecordId)
				.orElseThrow(() -> new ConsumerException(ConsumerException.Code.RECORD_DELETED));

		// 3. Verify at least one document exists.
		List<PosDocumentEntity> documents = this.documentRepository
				.findByPosRecordIdOrderBySequenceNumberAsc(posRecordId);
		if (documents.isEmpty()) {
			throw new ConsumerException(ConsumerException.Code.EXTRACTION_STATE_CONFLICT,
					"No documents found for POS record " + posRecordId);
		}

		// 4. Verify every document is COMPLETED.
		for (PosDocumentEntity document : documents) {
			if (document.getProcessingStatus() != DocumentProcessingStatus.COMPLETED) {
				throw new ConsumerException(ConsumerException.Code.EXTRACTION_STATE_CONFLICT,
						"Document " + document.getId() + " is not COMPLETED (status="
								+ document.getProcessingStatus() + ")");
			}
		}

		// 5. Verify exactly one version-1 result exists per document.
		for (PosDocumentEntity document : documents) {
			if (!this.ocrResultRepository.existsByDocumentIdAndPromptVersion(document.getId(), promptVersion)) {
				throw new ConsumerException(ConsumerException.Code.EXTRACTION_STATE_CONFLICT,
						"Document " + document.getId() + " has no version-" + promptVersion + " OCR result");
			}
		}
		long ocrCount = this.ocrResultRepository.countByPosRecordIdAndPromptVersion(posRecordId, promptVersion);
		if (ocrCount != documents.size()) {
			throw new ConsumerException(ConsumerException.Code.EXTRACTION_STATE_CONFLICT,
					"OCR result count " + ocrCount + " does not match document count " + documents.size());
		}

		// 6. All checks passed: complete the job and move the record.
		job.complete(now);
		job.setErrorCode(null);
		job.setErrorMessage(null);
		this.jobRepository.saveAndFlush(job);

		record.setStatus(PosRecordStatus.REVIEW_REQUIRED);
		record.setUpdatedAt(now);
		this.recordRepository.saveAndFlush(record);

		log.info("Ingestion job completed and record moved to REVIEW_REQUIRED (category=ocr-completion); jobId={}",
				jobId);
	}

	/**
	 * Returns a document to {@code PENDING} after a retryable failure.
	 */
	@Transactional
	public void markDocumentPending(UUID documentId) {
		PosDocumentEntity document = this.documentRepository.findById(documentId)
				.orElseThrow(() -> new ConsumerException(ConsumerException.Code.ID_MISMATCH));
		document.setProcessingStatus(DocumentProcessingStatus.PENDING);
		this.documentRepository.saveAndFlush(document);
	}

	/**
	 * Marks a document {@code FAILED} on a terminal failure.
	 */
	@Transactional
	public void markDocumentFailed(UUID documentId) {
		PosDocumentEntity document = this.documentRepository.findById(documentId)
				.orElseThrow(() -> new ConsumerException(ConsumerException.Code.ID_MISMATCH));
		document.setProcessingStatus(DocumentProcessingStatus.FAILED);
		this.documentRepository.saveAndFlush(document);
	}

}
