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
 * Persists the extracted PDFs and reconciles the job's terminal state in a
 * single SQLite transaction.
 *
 * <p>The transaction creates the {@code storage_object} rows, the
 * {@code pos_document} rows (one per PDF, all {@code UNKNOWN}/{@code PENDING}),
 * and updates the job to {@code COMPLETED}. The POS record remains
 * {@code PROCESSING} because OCR is a later task.
 *
 * <p>Idempotency: when a redelivered message arrives for a job that already
 * has matching {@code pos_document} rows, the rows are reused; any mismatch
 * on the immutable fields (sequence, storage object key, size, hash) is a
 * permanent {@link ConsumerException.Code#EXTRACTION_STATE_CONFLICT}.
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
		// documents match the proposed extraction, the call is a no-op
		// (still considered success). Mismatch is a permanent conflict.
		List<PosDocumentEntity> existing = this.posDocumentRepository
				.findByPosRecordIdOrderBySequenceNumberAsc(posRecordId);
		if (!existing.isEmpty()) {
			reconcileExisting(existing, pdfs);
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

		job.complete(now);
		this.ingestionJobRepository.saveAndFlush(job);

		log.info("Extraction persisted (category=persistence-success); posRecordId={}, jobId={}, pdfCount={}",
				posRecordId, jobId, pdfs.size());
	}

	private void reconcileExisting(List<PosDocumentEntity> existing, List<ExtractedPdf> proposed) {
		if (existing.size() != proposed.size()) {
			throw new ConsumerException(ConsumerException.Code.EXTRACTION_STATE_CONFLICT);
		}
		for (int i = 0; i < existing.size(); i++) {
			PosDocumentEntity doc = existing.get(i);
			ExtractedPdf pdf = proposed.get(i);
			if (doc.getSequenceNumber() != pdf.sequence()
					|| !doc.getId().equals(pdf.documentId())
					|| !doc.getStorageObject().getId().equals(pdf.storageObjectId())
					|| !doc.getStorageObject().getObjectKey().equals(pdf.objectKey())
					|| doc.getStorageObject().getByteSize() != pdf.byteSize()
					|| !doc.getStorageObject().getSha256().equalsIgnoreCase(pdf.sha256())) {
				throw new ConsumerException(ConsumerException.Code.EXTRACTION_STATE_CONFLICT);
			}
		}
		// Match: extraction is idempotent; nothing to write.
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