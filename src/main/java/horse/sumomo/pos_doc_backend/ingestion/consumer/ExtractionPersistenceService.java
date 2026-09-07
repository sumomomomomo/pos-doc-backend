package horse.sumomo.pos_doc_backend.ingestion.consumer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import horse.sumomo.pos_doc_backend.ingestion.consumer.ArchiveExtractionService.ExtractedPdf;
import horse.sumomo.pos_doc_backend.persistence.entity.IngestionJobEntity;
import horse.sumomo.pos_doc_backend.persistence.entity.PosDocumentEntity;
import horse.sumomo.pos_doc_backend.persistence.entity.PosRecordEntity;
import horse.sumomo.pos_doc_backend.persistence.entity.StorageObjectEntity;
import horse.sumomo.pos_doc_backend.persistence.model.DocumentProcessingStatus;
import horse.sumomo.pos_doc_backend.persistence.model.DocumentType;
import horse.sumomo.pos_doc_backend.persistence.repository.IngestionJobRepository;
import horse.sumomo.pos_doc_backend.persistence.repository.PosDocumentRepository;
import horse.sumomo.pos_doc_backend.persistence.repository.PosRecordRepository;
import horse.sumomo.pos_doc_backend.persistence.repository.StorageObjectRepository;

/**
 * Persists the extracted PDFs in a single SQLite transaction.
 *
 * <p>The transaction creates the {@code storage_object} rows and the
 * {@code pos_document} rows (one per PDF, all {@code UNKNOWN}/{@code PENDING}).
 * The job is <em>not</em> completed here: Task 9 completes it only after
 * every document has a durable version-1 OCR result. The POS record
 * remains {@code PROCESSING} until the OCR workflow moves it to
 * {@code REVIEW_REQUIRED}.
 *
 * <p>Idempotency: when a redelivered message arrives, the existing rows
 * are compared against the proposed extraction across every immutable
 * field — document id, storage object id, sequence, object key, original
 * filename, content type, byte size, SHA-256, document type, and
 * processing status. Any mismatch is a permanent
 * {@link ConsumerException.Code#EXTRACTION_STATE_CONFLICT} so the message
 * is sent to the DLQ.
 */
@Service
public class ExtractionPersistenceService {

	private static final Logger log = LoggerFactory.getLogger(ExtractionPersistenceService.class);

	private static final String PDF_CONTENT_TYPE = "application/pdf";
	private static final DocumentType INITIAL_TYPE = DocumentType.UNKNOWN;
	private static final DocumentProcessingStatus INITIAL_STATUS = DocumentProcessingStatus.PENDING;

	private final StorageObjectRepository storageObjectRepository;
	private final PosRecordRepository posRecordRepository;
	private final PosDocumentRepository posDocumentRepository;
	private final IngestionJobRepository ingestionJobRepository;

	public ExtractionPersistenceService(StorageObjectRepository storageObjectRepository,
			PosRecordRepository posRecordRepository, PosDocumentRepository posDocumentRepository,
			IngestionJobRepository ingestionJobRepository) {
		this.storageObjectRepository = Objects.requireNonNull(storageObjectRepository);
		this.posRecordRepository = Objects.requireNonNull(posRecordRepository);
		this.posDocumentRepository = Objects.requireNonNull(posDocumentRepository);
		this.ingestionJobRepository = Objects.requireNonNull(ingestionJobRepository);
	}

	/**
	 * Atomic commit of all storage and document rows plus the job's
	 * terminal state. Either all rows are written or none are.
	 */
	@Transactional
	public void persistExtraction(UUID posRecordId, UUID jobId, List<ExtractedPdf> pdfs, Instant now) {
		PosRecordEntity record = this.posRecordRepository.findByIdAndDeletedAtIsNull(posRecordId)
				.orElseThrow(() -> new ConsumerException(ConsumerException.Code.RECORD_DELETED));
		IngestionJobEntity job = this.ingestionJobRepository.findById(jobId)
				.orElseThrow(() -> new ConsumerException(ConsumerException.Code.ID_MISMATCH));

		if (job.getPosRecord() == null || !job.getPosRecord().getId().equals(posRecordId)) {
			throw new ConsumerException(ConsumerException.Code.ID_MISMATCH);
		}

		// Idempotency: if the job is already COMPLETED and the existing
		// documents match the proposed extraction across every immutable
		// field, the call is a no-op (still success). Mismatch is a
		// permanent conflict.
		List<PosDocumentEntity> existing = this.posDocumentRepository
				.findByPosRecordIdOrderBySequenceNumberAsc(posRecordId);
		if (!existing.isEmpty()) {
			reconcileExisting(existing, pdfs, job, now);
			return;
		}

		List<StorageObjectEntity> storages = new ArrayList<>(pdfs.size());
		for (ExtractedPdf pdf : pdfs) {
			StorageObjectEntity storage = new StorageObjectEntity(pdf.storageObjectId(), pdf.objectKey(),
					pdf.filenameSegment(), PDF_CONTENT_TYPE, pdf.byteSize(), pdf.sha256(), now);
			storage = this.storageObjectRepository.saveAndFlush(storage);
			storages.add(storage);
		}

		for (int i = 0; i < pdfs.size(); i++) {
			ExtractedPdf pdf = pdfs.get(i);
			StorageObjectEntity storage = storages.get(i);
			PosDocumentEntity document = new PosDocumentEntity(pdf.documentId(), record, storage, pdf.sequence(),
					INITIAL_TYPE, INITIAL_STATUS);
			this.posDocumentRepository.saveAndFlush(document);
		}

		// The job is NOT completed here: Task 9 completes it only after
		// every document has a durable version-1 OCR result. The OCR
		// workflow (DocumentOcrWorkflowService) runs after this method
		// returns and performs the final job completion.

		log.info("Extraction persisted (category=persistence-success); posRecordId={}, jobId={}, pdfCount={}",
				posRecordId, jobId, pdfs.size());
	}

	private void reconcileExisting(List<PosDocumentEntity> existing, List<ExtractedPdf> proposed,
			IngestionJobEntity job, Instant now) {
		if (existing.size() != proposed.size()) {
			throw new ConsumerException(ConsumerException.Code.EXTRACTION_STATE_CONFLICT);
		}
		for (int i = 0; i < existing.size(); i++) {
			PosDocumentEntity doc = existing.get(i);
			ExtractedPdf pdf = proposed.get(i);
			StorageObjectEntity storage = doc.getStorageObject();
			// Every immutable field must match. A mismatch on any of these
			// would mean the same POS record is being asked to point at
			// two different archives; that is a permanent conflict.
			if (!doc.getId().equals(pdf.documentId())
					|| doc.getSequenceNumber() != pdf.sequence()
					|| doc.getDocumentType() != INITIAL_TYPE
					|| doc.getProcessingStatus() != INITIAL_STATUS
					|| !storage.getId().equals(pdf.storageObjectId())
					|| !storage.getObjectKey().equals(pdf.objectKey())
					|| !Objects.equals(storage.getOriginalFilename(), pdf.filenameSegment())
					|| !PDF_CONTENT_TYPE.equals(storage.getContentType())
					|| storage.getByteSize() != pdf.byteSize()
					|| !storage.getSha256().equalsIgnoreCase(pdf.sha256())) {
				throw new ConsumerException(ConsumerException.Code.EXTRACTION_STATE_CONFLICT);
			}
		}
		// Match. The job is NOT completed here: Task 9 completes it only
		// after every document has a durable version-1 OCR result. The
		// OCR workflow runs after this method returns.
		log.debug("Reconciled existing extraction (category=persistence-idempotent); jobId={}, pdfCount={}",
				job.getId(), existing.size());
	}

	/**
	 * Counts the persisted document rows for one POS record. Used by the
	 * consumer to decide whether a previously COMPLETED job is in an
	 * idempotent-noop state.
	 */
	@Transactional(readOnly = true)
	public long countDocuments(UUID posRecordId) {
		return this.posDocumentRepository.findByPosRecordIdOrderBySequenceNumberAsc(posRecordId).size();
	}

}