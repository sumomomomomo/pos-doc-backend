package horse.sumomo.pos_doc_backend.ingestion.consumer;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import horse.sumomo.pos_doc_backend.ocr.application.FirstPageOcrService;
import horse.sumomo.pos_doc_backend.ocr.model.OcrResult;
import horse.sumomo.pos_doc_backend.ocr.service.OcrException;
import horse.sumomo.pos_doc_backend.persistence.entity.PosDocumentEntity;
import horse.sumomo.pos_doc_backend.rendering.service.RenderingException;

/**
 * Workflow service that invokes the existing {@link FirstPageOcrService}
 * for every extracted document and persists the durable per-document OCR
 * result.
 *
 * <p>This service is <em>not</em> annotated {@code @Transactional}. Each
 * database state change is a separate short transaction through
 * {@link DocumentOcrPersistenceService}; the OCR HTTP call happens
 * outside any transaction.
 *
 * <p>Documents are processed in deterministic ZIP sequence order, one at
 * a time (no parallelism). A completed version-1 result is never OCRed
 * again on redelivery. A retryable failure returns the current document
 * to {@code PENDING} and preserves completed earlier documents. A
 * non-retryable failure marks the current document, job, and POS record
 * {@code FAILED}.
 *
 * <p>No OCR text, response JSON, image bytes, PDF contents, filesystem
 * paths, or remote response bodies are ever logged or placed in an
 * exception message.
 */
@Service
public class DocumentOcrWorkflowService {

	private static final Logger log = LoggerFactory.getLogger(DocumentOcrWorkflowService.class);

	/** Task 9 processes prompt version 1, matching Task 8. */
	static final int PROMPT_VERSION = 1;

	private final DocumentOcrPersistenceService persistenceService;
	private final FirstPageOcrService ocrService;

	public DocumentOcrWorkflowService(DocumentOcrPersistenceService persistenceService,
			FirstPageOcrService ocrService) {
		this.persistenceService = Objects.requireNonNull(persistenceService);
		this.ocrService = Objects.requireNonNull(ocrService);
	}

	/**
	 * Runs the OCR workflow for one ingestion job: processes every
	 * extracted document in sequence order, persists a version-1 OCR
	 * result for each, then completes the job and moves the POS record to
	 * {@code REVIEW_REQUIRED}.
	 *
	 * <p>Throws {@link ConsumerException} on categorized failure; the
	 * listener's retry/recoverer decides whether to retry or DLQ.
	 */
	public void runOcrWorkflow(UUID posRecordId, UUID jobId) {
		List<PosDocumentEntity> documents = this.persistenceService.loadDocumentsInSequenceOrder(posRecordId);
		if (documents.isEmpty()) {
			throw new ConsumerException(ConsumerException.Code.EXTRACTION_STATE_CONFLICT,
					"No documents found for POS record " + posRecordId);
		}

		for (PosDocumentEntity document : documents) {
			processDocument(document.getId());
		}

		// Single transactional verification + completion: verifies the
		// job-to-record relationship, record active state, document
		// count, all documents COMPLETED, and exactly one version-1 OCR
		// result per document, then completes the job and moves the
		// record to REVIEW_REQUIRED — all atomically.
		this.persistenceService.completeIfAllDocumentsOcrComplete(jobId, posRecordId, PROMPT_VERSION,
				Instant.now());
	}

	/**
	 * Processes a single document: inspects its OCR state, calls OCR if
	 * needed, and persists the result.
	 *
	 * @throws ConsumerException with a retryable or nonretryable code on
	 *             failure
	 */
	private void processDocument(UUID documentId) {
		DocumentOcrPersistenceService.DocumentOcrState state = this.persistenceService
				.inspectAndPrepare(documentId, PROMPT_VERSION);

		switch (state) {
			case ALREADY_COMPLETED, STATUS_REPAIRED_COMPLETED -> {
				// Version-1 result already exists; skip OCR.
				log.debug("Document already OCR-complete (category=ocr-skip); documentId={}", documentId);
				return;
			}
			case READY_FOR_OCR -> {
				// Call OCR outside any transaction.
				OcrResult result;
				try {
					result = this.ocrService.recognize(documentId);
				}
				catch (OcrException e) {
					handleOcrFailure(documentId, e);
					return;
				}
				catch (RenderingException e) {
					handleRenderingFailure(documentId, e);
					return;
				}

				// Validate the returned document ID and prompt version.
				// These are terminal invariant failures: mark the document
				// FAILED before throwing so the final state is consistent.
				if (!documentId.equals(result.documentId())) {
					this.persistenceService.markDocumentFailed(documentId);
					log.warn("Terminal OCR identity failure (category=ocr-terminal); documentId={}, "
							+ "reason=document-id-mismatch");
					throw new ConsumerException(ConsumerException.Code.EXTRACTION_STATE_CONFLICT,
							"OCR result document ID mismatch for document " + documentId);
				}
				if (result.promptVersion() != PROMPT_VERSION) {
					this.persistenceService.markDocumentFailed(documentId);
					log.warn("Terminal OCR identity failure (category=ocr-terminal); documentId={}, "
							+ "reason=prompt-version-mismatch");
					throw new ConsumerException(ConsumerException.Code.EXTRACTION_STATE_CONFLICT,
							"OCR result prompt version mismatch for document " + documentId);
				}

				// Persist the result in a new short transaction.
				this.persistenceService.persistOcrResult(documentId, PROMPT_VERSION, result.text(),
						result.model(), result.finishReason(), Instant.now());
				return;
			}
		}
	}

	/**
	 * Handles an OCR failure: retryable failures return the document to
	 * {@code PENDING}; non-retryable failures mark the document
	 * {@code FAILED}.
	 */
	private void handleOcrFailure(UUID documentId, OcrException e) {
		if (e.getCode().retryable()) {
			this.persistenceService.markDocumentPending(documentId);
			log.warn("Retryable OCR failure (category=ocr-retryable); documentId={}, code={}", documentId,
					e.getCode().code());
			throw new ConsumerException(ConsumerException.Code.EXTRACTION_TRANSIENT_FAILURE, e);
		}
		this.persistenceService.markDocumentFailed(documentId);
		log.warn("Nonretryable OCR failure (category=ocr-terminal); documentId={}, code={}", documentId,
				e.getCode().code());
		throw new ConsumerException(ConsumerException.Code.EXTRACTION_STATE_CONFLICT, e);
	}

	/**
	 * Handles a rendering failure using the stable Task 7 error
	 * classification. Interrupted and temporary storage/I/O unavailability
	 * are retryable; missing/corrupt/encrypted/invalid/oversized/
	 * unsupported PDF or invalid rendered output is terminal.
	 */
	private void handleRenderingFailure(UUID documentId, RenderingException e) {
		boolean retryable = isRenderingRetryable(e.getCode());
		if (retryable) {
			this.persistenceService.markDocumentPending(documentId);
			log.warn("Retryable rendering failure (category=render-retryable); documentId={}, code={}", documentId,
					e.getCode().code());
			throw new ConsumerException(ConsumerException.Code.EXTRACTION_TRANSIENT_FAILURE, e);
		}
		this.persistenceService.markDocumentFailed(documentId);
		log.warn("Nonretryable rendering failure (category=render-terminal); documentId={}, code={}", documentId,
				e.getCode().code());
		throw new ConsumerException(ConsumerException.Code.EXTRACTION_STATE_CONFLICT, e);
	}

	/**
	 * Exhaustive mapping of every Task 7 {@link RenderingException.Code}
	 * to a retryable or terminal classification. There is no permissive
	 * {@code default} that accidentally makes a new code retryable.
	 */
	static boolean isRenderingRetryable(RenderingException.Code code) {
		return switch (code) {
			// Retryable: interrupted and temporary storage/I/O unavailability.
			case PDF_STORAGE_UNAVAILABLE, RENDER_INTERRUPTED, TEMP_STORAGE_UNAVAILABLE -> true;
			// Terminal: missing/corrupt/encrypted/invalid/oversized/
			// unsupported PDF or invalid rendered output.
			case DOCUMENT_NOT_FOUND, DOCUMENT_DELETED, PDF_METADATA_INVALID, PDF_OBJECT_MISSING,
					PDF_SIZE_MISMATCH, PDF_HASH_MISMATCH, PDF_INVALID, PAGE_DIMENSIONS_INVALID,
					RENDER_LIMIT_EXCEEDED, RENDER_FAILED -> false;
		};
	}

}
