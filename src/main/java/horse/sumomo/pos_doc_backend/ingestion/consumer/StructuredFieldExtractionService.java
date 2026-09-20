package horse.sumomo.pos_doc_backend.ingestion.consumer;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import horse.sumomo.pos_doc_backend.ingestion.application.DocumentCandidateSelector;
import horse.sumomo.pos_doc_backend.ingestion.application.DocumentSnapshot;
import horse.sumomo.pos_doc_backend.ingestion.application.ExtractionBackoff;
import horse.sumomo.pos_doc_backend.ocr.api.LlamaCppOcrProperties;
import horse.sumomo.pos_doc_backend.ocr.application.FieldAnswerParse;
import horse.sumomo.pos_doc_backend.ocr.application.FieldAnswerParser;
import horse.sumomo.pos_doc_backend.ocr.application.FieldExtractionPrompts;
import horse.sumomo.pos_doc_backend.ocr.client.LlamaCppOcrClient;
import horse.sumomo.pos_doc_backend.ocr.model.OcrResult;
import horse.sumomo.pos_doc_backend.ocr.service.OcrException;
import horse.sumomo.pos_doc_backend.persistence.entity.PosFieldExtractionEntity;
import horse.sumomo.pos_doc_backend.persistence.entity.PosFieldExtractionId;
import horse.sumomo.pos_doc_backend.persistence.model.ExtractionField;
import horse.sumomo.pos_doc_backend.persistence.model.ExtractionOutcome;
import horse.sumomo.pos_doc_backend.rendering.application.FirstPageRenderPreparationService;
import horse.sumomo.pos_doc_backend.rendering.model.RenderedFirstPage;
import horse.sumomo.pos_doc_backend.rendering.service.RenderingException;

/**
 * Best-effort structured field-extraction workflow.
 *
 * <p>For a record's PDFs it selects exactly one candidate document, renders the
 * candidate's first page once, and makes up to one structured HTTP call per
 * unresolved business field (three fields in a fixed order), reusing the same
 * rendered PNG for every call. Non-candidate documents are marked
 * {@code SKIPPED}. Every model call is followed by a durable outcome
 * ({@code RESOLVED}/{@code UNKNOWN}/{@code FAILED}) before the candidate is
 * marked {@code COMPLETED}; resolved values are applied to the record with a
 * null-guarded optimistic update. When there is no candidate (two to ten PDFs
 * with no {@code LAPPe.pdf} match), all PDFs are marked {@code SKIPPED} and no
 * model calls are made. The workflow then completes the job and moves the
 * record to {@code REVIEW_REQUIRED} (null business fields are allowed).
 *
 * <p>This service is <em>not</em> annotated with {@code @Transactional}: each
 * persistence step is its own short transaction (via
 * {@link FieldExtractionPersistenceService}), and the render/HTTP calls run
 * outside any transaction. All failures are surfaced as
 * {@link ConsumerException} so the listener's bounded retry/DLQ logic applies.
 *
 * <p>Never logs OCR text, prompts, or PII.
 */
@Service
public class StructuredFieldExtractionService {

	private static final Logger log = LoggerFactory.getLogger(StructuredFieldExtractionService.class);

	private static final List<ExtractionField> FIELD_ORDER = List.of(ExtractionField.POLICYHOLDER_NAME,
			ExtractionField.CONSULTANT_NAME, ExtractionField.POLICY_CREATE_DATE);

	private static final String ERROR_CODE_INVALID_ANSWER = "FIELD_ANSWER_INVALID";

	private final FirstPageRenderPreparationService renderService;
	private final LlamaCppOcrClient ocrClient;
	private final LlamaCppOcrProperties properties;
	private final FieldExtractionPersistenceService persistence;
	private final ExtractionBackoff backoff;

	public StructuredFieldExtractionService(FirstPageRenderPreparationService renderService,
			LlamaCppOcrClient ocrClient, LlamaCppOcrProperties properties,
			FieldExtractionPersistenceService persistence, ExtractionBackoff backoff) {
		this.renderService = renderService;
		this.ocrClient = ocrClient;
		this.properties = properties;
		this.persistence = persistence;
		this.backoff = backoff;
	}

	/**
	 * Runs the structured field-extraction workflow for one record, then
	 * completes the job and moves the record to {@code REVIEW_REQUIRED}.
	 *
	 * @param posRecordId the POS record id
	 * @param jobId the ingestion job id
	 */
	public void runFieldExtraction(UUID posRecordId, UUID jobId) {
		int version = FieldExtractionPrompts.PROMPT_VERSION;

		List<DocumentSnapshot> pdfs = this.persistence.snapshotPosDocuments(posRecordId);
		Optional<UUID> candidate = DocumentCandidateSelector.select(pdfs);

		if (candidate.isPresent()) {
			UUID candidateId = candidate.get();
			List<UUID> nonCandidates = pdfs.stream().map(DocumentSnapshot::documentId)
					.filter(id -> !id.equals(candidateId)).toList();
			this.persistence.markDocumentsSkipped(nonCandidates);
			this.processCandidate(posRecordId, candidateId, version);
		}
		else {
			// Two to ten PDFs with no LAPPe.pdf match: no candidate. Skip all PDFs.
			this.persistence.markDocumentsSkipped(pdfs.stream().map(DocumentSnapshot::documentId).toList());
		}

		this.persistence.completeWorkflow(jobId, posRecordId, Instant.now());
	}

	private void processCandidate(UUID posRecordId, UUID candidateId, int version) {
		this.persistence.markDocumentProcessing(candidateId);
		try (RenderedFirstPage page = this.openPage(candidateId)) {
			for (ExtractionField field : FIELD_ORDER) {
				this.processField(posRecordId, candidateId, field, version, page);
			}
			this.persistence.markDocumentCompleted(candidateId);
		}
		log.debug("Candidate processed; documentId={}", candidateId);
	}

	/**
	 * Renders the candidate's first page once, converting any rendering failure
	 * into a {@link ConsumerException} for the listener's retry/DLQ logic.
	 */
	private RenderedFirstPage openPage(UUID candidateId) {
		try {
			return this.renderService.prepare(candidateId);
		}
		catch (RenderingException ex) {
			throw toConsumerException(ex);
		}
	}

	private void processField(UUID posRecordId, UUID candidateId, ExtractionField field, int version,
			RenderedFirstPage page) {
		// Refresh which fields are still null; skip fields already resolved.
		Set<ExtractionField> resolved = this.persistence.resolvedBusinessFields(posRecordId);
		if (resolved.contains(field)) {
			log.debug("Field already resolved; skipping; field={}", field.name());
			return;
		}

		// A durable outcome for (candidate, field, version) is authoritative: no model call.
		Optional<PosFieldExtractionEntity> existing = this.persistence.loadOutcome(candidateId, field, version);
		if (existing.isPresent()) {
			PosFieldExtractionEntity row = existing.get();
			if (row.getOutcome() == ExtractionOutcome.RESOLVED) {
				this.persistence.applyResolvedField(posRecordId, field, row.getValueText());
			}
			log.debug("Durable outcome present; no model call; field={}; outcome={}", field.name(),
					row.getOutcome());
			return;
		}

		// No durable outcome: run up to maxAttempts attempts.
		String prompt = FieldExtractionPrompts.promptFor(field);
		int maxAttempts = this.properties.maxAttempts();
		String lastErrorCode = null;
		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			try {
				OcrResult ocr = this.ocrClient.recognize(page, prompt, version);
				FieldAnswerParse parse = FieldAnswerParser.parse(field, ocr.text());
				switch (parse.kind()) {
					case RESOLVED -> {
						this.upsertOutcome(candidateId, field, version, ExtractionOutcome.RESOLVED,
								parse.value(), ocr.model(), ocr.finishReason(), attempt, null);
						this.persistence.applyResolvedField(posRecordId, field, parse.value());
						return;
					}
					case UNKNOWN -> {
						this.upsertOutcome(candidateId, field, version, ExtractionOutcome.UNKNOWN, null,
								ocr.model(), ocr.finishReason(), attempt, null);
						return;
					}
					case INVALID -> {
						// A blank/unparseable/not-matching answer is not UNKNOWN:
						// treat it as a failed attempt and retry.
						lastErrorCode = ERROR_CODE_INVALID_ANSWER;
					}
				}
			}
			catch (OcrException ex) {
				if (ex.getCode().retryable()) {
					lastErrorCode = ex.getCode().code();
				}
				else {
					this.upsertOutcome(candidateId, field, version, ExtractionOutcome.FAILED, null,
							this.properties.model(), null, attempt, ex.getCode().code());
					return;
				}
			}
			if (attempt < maxAttempts) {
				this.backoff.sleep(this.properties.retryBackoffMs());
			}
		}
		// All attempts failed (retryable failures and/or invalid answers).
		this.upsertOutcome(candidateId, field, version, ExtractionOutcome.FAILED, null,
				this.properties.model(), null, maxAttempts, lastErrorCode);
	}

	private void upsertOutcome(UUID candidateId, ExtractionField field, int version, ExtractionOutcome outcome,
			String value, String model, String finishReason, int attempts, String errorCode) {
		PosFieldExtractionId id = new PosFieldExtractionId(candidateId, field.name(), version);
		PosFieldExtractionEntity entity = new PosFieldExtractionEntity(id, outcome, value, model, finishReason,
				attempts, errorCode, Instant.now());
		this.persistence.upsertExtractionOutcome(entity);
		log.debug("Field outcome durable; field={}; outcome={}; attempts={}", field.name(), outcome, attempts);
	}

	private ConsumerException toConsumerException(RenderingException ex) {
		if (ex.getCode().retryable()) {
			return new ConsumerException(ConsumerException.Code.EXTRACTION_TRANSIENT_FAILURE, ex);
		}
		return new ConsumerException(ConsumerException.Code.EXTRACTION_STATE_CONFLICT, ex);
	}

}
