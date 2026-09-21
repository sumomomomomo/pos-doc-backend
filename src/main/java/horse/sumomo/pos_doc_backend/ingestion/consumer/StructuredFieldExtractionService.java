package horse.sumomo.pos_doc_backend.ingestion.consumer;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
 * <p>For a record's PDFs it selects the candidate documents (every
 * case-sensitive {@code LAPPe.pdf}, or the first up to ten PDFs when none
 * match), then processes them <em>sequentially</em>, requesting only the fields
 * that are still unresolved, and stopping as soon as every field resolves. Each
 * candidate's first page is rendered <em>once</em> and reused across its fields.
 *
 * <p>Outcomes are best-effort and durable: every model call is followed by a
 * durable {@code RESOLVED}/{@code UNKNOWN}/{@code FAILED} outcome, and only the
 * value stored in the winning (authoritative) upsert row is applied to the
 * record. A field left {@code UNKNOWN}/{@code FAILED} keeps the record's
 * business field null. Non-candidate and not-needed documents are marked
 * {@code SKIPPED}; the workflow always completes the job and moves the record to
 * {@code REVIEW_REQUIRED} (null business fields are allowed).
 *
 * <p>Failure handling:
 * <ul>
 *   <li>A <em>permanent</em> render failure for a candidate (corrupt/encrypted/
 *       invalid PDF) marks that candidate {@code FAILED} and continues with the
 *       next candidate.</li>
 *   <li>A <em>temporary</em> storage/rendering failure, and any interruption,
 *       escapes as a retryable {@link ConsumerException} for the listener's
 *       bounded retry / DLQ path (an interruption preserves the interrupt
 *       flag).</li>
 *   <li>Per-field model failures are retried within the field (bounded); a
 *       non-retryable model error is recorded as a durable {@code FAILED}
 *       outcome (best-effort), and an answer rejected by validation is retried.</li>
 * </ul>
 *
 * <p>This service is <em>not</em> annotated with {@code @Transactional}: each
 * persistence step is its own short transaction (via
 * {@link FieldExtractionPersistenceService}), and the render/HTTP calls run
 * outside any transaction.
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
		List<DocumentSnapshot> candidates = DocumentCandidateSelector.select(pdfs);
		Set<UUID> candidateIds = candidates.stream().map(DocumentSnapshot::documentId)
				.collect(Collectors.toSet());
		List<UUID> nonCandidates = pdfs.stream().map(DocumentSnapshot::documentId)
				.filter(id -> !candidateIds.contains(id)).toList();
		this.persistence.markDocumentsSkipped(nonCandidates);

		for (int i = 0; i < candidates.size(); i++) {
			if (allFieldsResolved(posRecordId)) {
				// Every field is resolved: this candidate and the rest are not
				// needed, so mark them SKIPPED and stop.
				List<UUID> notNeeded = candidates.subList(i, candidates.size()).stream()
						.map(DocumentSnapshot::documentId).toList();
				this.persistence.markDocumentsSkipped(notNeeded);
				break;
			}
			this.processCandidate(posRecordId, candidates.get(i).documentId(), version);
		}

		this.persistence.completeWorkflow(jobId, posRecordId, Instant.now());
	}

	private boolean allFieldsResolved(UUID posRecordId) {
		return this.persistence.resolvedBusinessFields(posRecordId).containsAll(FIELD_ORDER);
	}

	private void processCandidate(UUID posRecordId, UUID candidateId, int version) {
		this.persistence.markDocumentProcessing(candidateId);
		RenderedFirstPage page;
		try {
			page = this.renderService.prepare(candidateId);
		}
		catch (RenderingException ex) {
			if (ex.getCode().retryable()) {
				// Temporary storage/rendering failure, or an interruption: escape to
				// the listener's bounded retry / DLQ path. An interruption preserves
				// the interrupt flag.
				if (ex.getCode() == RenderingException.Code.RENDER_INTERRUPTED) {
					Thread.currentThread().interrupt();
				}
				throw new ConsumerException(ConsumerException.Code.EXTRACTION_TRANSIENT_FAILURE, ex);
			}
			// Permanent (corrupt/encrypted/invalid PDF): mark this candidate FAILED and
			// continue with the next candidate (best-effort; the job still completes).
			this.persistence.markDocumentFailed(candidateId);
			log.debug("Candidate render failed permanently; marked FAILED; documentId={}", candidateId);
			return;
		}
		try (page) {
			Set<ExtractionField> alreadyResolved = this.persistence.resolvedBusinessFields(posRecordId);
			for (ExtractionField field : FIELD_ORDER) {
				if (alreadyResolved.contains(field)) {
					continue;
				}
				this.processField(posRecordId, candidateId, field, version, page);
			}
			this.persistence.markDocumentCompleted(candidateId);
		}
		log.debug("Candidate processed; documentId={}", candidateId);
	}

	private void processField(UUID posRecordId, UUID candidateId, ExtractionField field, int version,
			RenderedFirstPage page) {
		// A durable outcome for (candidate, field, version) is authoritative: no model
		// call; apply only if the durable row is RESOLVED.
		Optional<PosFieldExtractionEntity> existing = this.persistence.loadOutcome(candidateId, field, version);
		if (existing.isPresent()) {
			this.applyIfResolved(posRecordId, field, existing.get());
			return;
		}

		String prompt = FieldExtractionPrompts.promptFor(field);
		int maxAttempts = this.properties.maxAttempts();
		String lastErrorCode = null;
		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			OcrResult ocr;
			FieldAnswerParse parse;
			try {
				ocr = this.ocrClient.recognize(page, prompt, version);
				parse = FieldAnswerParser.parse(field, ocr.text());
			}
			catch (OcrException ex) {
				if (ex.getCode() == OcrException.Code.OCR_INTERRUPTED) {
					// Preserve the interrupt flag and escape to the RabbitMQ retry path;
					// an interruption must not become a persisted FAILED outcome.
					Thread.currentThread().interrupt();
					throw new ConsumerException(ConsumerException.Code.EXTRACTION_TRANSIENT_FAILURE, ex);
				}
				if (!ex.getCode().retryable()) {
					PosFieldExtractionEntity row = this.upsertOutcome(candidateId, field, version,
							ExtractionOutcome.FAILED, null, this.properties.model(), null, attempt,
							ex.getCode().code());
					this.applyIfResolved(posRecordId, field, row);
					return;
				}
				// Retryable transport/service failure: retry.
				lastErrorCode = ex.getCode().code();
				this.sleepUnlessLastAttempt(attempt, maxAttempts);
				continue;
			}
			if (parse.kind() == FieldAnswerParse.ParseKind.INVALID) {
				// Answer rejected by field validation: retry.
				lastErrorCode = ERROR_CODE_INVALID_ANSWER;
				this.sleepUnlessLastAttempt(attempt, maxAttempts);
				continue;
			}
			ExtractionOutcome outcome = parse.kind() == FieldAnswerParse.ParseKind.RESOLVED
					? ExtractionOutcome.RESOLVED : ExtractionOutcome.UNKNOWN;
			String value = outcome == ExtractionOutcome.RESOLVED ? parse.value() : null;
			PosFieldExtractionEntity row = this.upsertOutcome(candidateId, field, version, outcome, value,
					ocr.model(), ocr.finishReason(), attempt, null);
			this.applyIfResolved(posRecordId, field, row);
			return;
		}
		// All attempts exhausted (retryable failures and/or invalid answers): FAILED.
		PosFieldExtractionEntity row = this.upsertOutcome(candidateId, field, version, ExtractionOutcome.FAILED,
				null, this.properties.model(), null, maxAttempts, lastErrorCode);
		this.applyIfResolved(posRecordId, field, row);
	}

	/**
	 * Applies a resolved value to the record only when the supplied durable row is
	 * {@code RESOLVED}. The applied value is always the row's stored
	 * {@code value_text} (never a value proposed locally), so a losing caller in an
	 * upsert race cannot apply an unrecorded value.
	 */
	private void applyIfResolved(UUID posRecordId, ExtractionField field, PosFieldExtractionEntity row) {
		if (row.getOutcome() == ExtractionOutcome.RESOLVED) {
			this.persistence.applyResolvedField(posRecordId, field, row.getValueText());
		}
	}

	private PosFieldExtractionEntity upsertOutcome(UUID candidateId, ExtractionField field, int version,
			ExtractionOutcome outcome, String value, String model, String finishReason, int attempts,
			String errorCode) {
		PosFieldExtractionId id = new PosFieldExtractionId(candidateId, field.name(), version);
		PosFieldExtractionEntity proposed = new PosFieldExtractionEntity(id, outcome, value, model, finishReason,
				attempts, errorCode, Instant.now());
		PosFieldExtractionEntity durable = this.persistence.upsertExtractionOutcome(proposed);
		log.debug("Field outcome durable; field={}; outcome={}; attempts={}", field.name(), durable.getOutcome(),
				durable.getAttemptCount());
		return durable;
	}

	private void sleepUnlessLastAttempt(int attempt, int maxAttempts) {
		if (attempt < maxAttempts) {
			this.backoff.sleep(this.properties.retryBackoffMs());
		}
	}

}
