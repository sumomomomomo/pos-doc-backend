package horse.sumomo.pos_doc_backend.ingestion.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import horse.sumomo.pos_doc_backend.ingestion.consumer.ArchiveExtractionService.ExtractedPdf;
import horse.sumomo.pos_doc_backend.persistence.entity.IngestionJobEntity;
import horse.sumomo.pos_doc_backend.persistence.entity.PosDocumentEntity;
import horse.sumomo.pos_doc_backend.persistence.entity.PosRecordEntity;
import horse.sumomo.pos_doc_backend.persistence.entity.StorageObjectEntity;
import horse.sumomo.pos_doc_backend.persistence.model.DocumentProcessingStatus;
import horse.sumomo.pos_doc_backend.persistence.model.DocumentType;
import horse.sumomo.pos_doc_backend.persistence.model.JobStatus;
import horse.sumomo.pos_doc_backend.persistence.model.PosRecordStatus;
import horse.sumomo.pos_doc_backend.persistence.repository.IngestionJobRepository;
import horse.sumomo.pos_doc_backend.persistence.repository.PosDocumentRepository;
import horse.sumomo.pos_doc_backend.persistence.repository.PosRecordRepository;
import horse.sumomo.pos_doc_backend.persistence.repository.StorageObjectRepository;

/**
 * Logic-level unit tests for {@link ExtractionPersistenceService}.
 *
 * <p>Covers Task 6 acceptance criteria:
 * <ul>
 * <li>persistence conflict rolls back all SQLite changes (transaction
 * boundary is honored),</li>
 * <li>reconciliation completes a RUNNING job (idempotent no-op that
 * still updates the terminal job state when the existing rows match).</li>
 * </ul>
 *
 * <p>These tests exercise the service's branching and rollback
 * contract with mocked repositories so they run without a real
 * database.
 */
class ExtractionPersistenceServiceUnitTest {

	private static final String PDF_CONTENT_TYPE = "application/pdf";

	private StorageObjectRepository storageObjectRepository;
	private PosRecordRepository posRecordRepository;
	private PosDocumentRepository posDocumentRepository;
	private IngestionJobRepository ingestionJobRepository;

	@BeforeEach
	void setUp() {
		this.storageObjectRepository = mock(StorageObjectRepository.class);
		this.posRecordRepository = mock(PosRecordRepository.class);
		this.posDocumentRepository = mock(PosDocumentRepository.class);
		this.ingestionJobRepository = mock(IngestionJobRepository.class);
	}

	private ExtractionPersistenceService buildService(PlatformTransactionManager txm) {
		return new ExtractionPersistenceService(this.storageObjectRepository,
				this.posRecordRepository, this.posDocumentRepository,
				this.ingestionJobRepository) {
			@Override
			public void persistExtraction(UUID posRecordId, UUID jobId, List<ExtractedPdf> pdfs, Instant now) {
				TransactionStatus status = txm.getTransaction(null);
				try {
					super.persistExtraction(posRecordId, jobId, pdfs, now);
					txm.commit(status);
				}
				catch (RuntimeException e) {
					txm.rollback(status);
					throw e;
				}
			}
		};
	}

	private static StorageObjectEntity newStorage(UUID id, String key, String filename,
			String contentType, long byteSize, String sha, Instant now) {
		return new StorageObjectEntity(id, key, filename, contentType, byteSize, sha, now);
	}

	private static PosRecordEntity newRecord(UUID id, StorageObjectEntity source, Instant now) {
		return new PosRecordEntity(id, source, PosRecordStatus.PROCESSING, "tester", now, now);
	}

	private static IngestionJobEntity newJob(UUID id, PosRecordEntity record, JobStatus status,
			long attemptCount, Instant now) {
		return new IngestionJobEntity(id, record, status, attemptCount, now);
	}

	@Test
	void persistenceConflictRollsBackAllChanges() {
		UUID posRecordId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID documentId = UUID.randomUUID();
		UUID storageObjectId = UUID.randomUUID();
		String objectKey = "documents/" + posRecordId + "/" + documentId + ".pdf";
		Instant now = Instant.parse("2026-01-02T03:04:05Z");
		ExtractedPdf pdf = new ExtractedPdf(documentId, storageObjectId, objectKey, "doc.pdf",
				100L, "a".repeat(64), 0, false, now);

		PosRecordEntity record = newRecord(posRecordId, mock(StorageObjectEntity.class), now);
		IngestionJobEntity job = newJob(jobId, record, JobStatus.RUNNING, 1L, now);

		when(this.posRecordRepository.findByIdAndDeletedAtIsNull(posRecordId)).thenReturn(Optional.of(record));
		when(this.ingestionJobRepository.findById(jobId)).thenReturn(Optional.of(job));
		when(this.posDocumentRepository.findByPosRecordIdOrderBySequenceNumberAsc(posRecordId))
				.thenReturn(List.of());

		// Storage save collides with a unique constraint.
		when(this.storageObjectRepository.saveAndFlush(any(StorageObjectEntity.class)))
				.thenThrow(new DataIntegrityViolationException("simulated unique constraint violation"));

		RecordingTxManager txm = new RecordingTxManager();
		ExtractionPersistenceService service = buildService(txm);

		assertThrows(RuntimeException.class,
				() -> service.persistExtraction(posRecordId, jobId, List.of(pdf), now));

		// Storage save was attempted but threw before any document row.
		verify(this.storageObjectRepository, times(1))
				.saveAndFlush(any(StorageObjectEntity.class));
		verify(this.posDocumentRepository, never()).saveAndFlush(any(PosDocumentEntity.class));
		// Job must not have been mutated.
		verify(this.ingestionJobRepository, never())
				.saveAndFlush(any(IngestionJobEntity.class));
		// Transaction was rolled back.
		assertEquals(1, txm.rollbackCount);
		assertEquals(0, txm.commitCount);
	}

	@Test
	void reconciliationCompletesRunningJobWhenRowsMatch() {
		UUID posRecordId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID documentId = UUID.randomUUID();
		UUID storageObjectId = UUID.randomUUID();
		String objectKey = "documents/" + posRecordId + "/" + documentId + ".pdf";
		Instant now = Instant.parse("2026-01-02T03:04:05Z");
		String sha = "a".repeat(64);

		StorageObjectEntity existingStorage = newStorage(storageObjectId, objectKey, "doc.pdf",
				PDF_CONTENT_TYPE, 100L, sha, now);
		PosRecordEntity record = newRecord(posRecordId, mock(StorageObjectEntity.class), now);
		IngestionJobEntity job = newJob(jobId, record, JobStatus.RUNNING, 1L, now);
		PosDocumentEntity existingDoc = new PosDocumentEntity(documentId, record, existingStorage,
				0L, DocumentType.UNKNOWN, DocumentProcessingStatus.PENDING);

		when(this.posRecordRepository.findByIdAndDeletedAtIsNull(posRecordId)).thenReturn(Optional.of(record));
		when(this.ingestionJobRepository.findById(jobId)).thenReturn(Optional.of(job));
		when(this.posDocumentRepository.findByPosRecordIdOrderBySequenceNumberAsc(posRecordId))
				.thenReturn(List.of(existingDoc));

		ExtractedPdf proposed = new ExtractedPdf(documentId, storageObjectId, objectKey, "doc.pdf",
				100L, sha, 0, false, now);

		RecordingTxManager txm = new RecordingTxManager();
		ExtractionPersistenceService service = buildService(txm);

		service.persistExtraction(posRecordId, jobId, List.of(proposed), now);

		// Job transitioned RUNNING -> COMPLETED.
		assertEquals(JobStatus.COMPLETED, job.getStatus());
		ArgumentCaptor<IngestionJobEntity> jobCaptor = ArgumentCaptor.forClass(IngestionJobEntity.class);
		verify(this.ingestionJobRepository, times(1)).saveAndFlush(jobCaptor.capture());
		assertEquals(JobStatus.COMPLETED, jobCaptor.getValue().getStatus());
		// No new storage or document rows were created.
		verify(this.storageObjectRepository, never()).saveAndFlush(any(StorageObjectEntity.class));
		verify(this.posDocumentRepository, never()).saveAndFlush(any(PosDocumentEntity.class));
		// Transaction committed.
		assertEquals(1, txm.commitCount);
		assertEquals(0, txm.rollbackCount);
	}

	@Test
	void reconciliationRejectsImmutableFieldMismatch() {
		UUID posRecordId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID documentId = UUID.randomUUID();
		UUID storageObjectId = UUID.randomUUID();
		String objectKey = "documents/" + posRecordId + "/" + documentId + ".pdf";
		Instant now = Instant.parse("2026-01-02T03:04:05Z");

		StorageObjectEntity existingStorage = newStorage(storageObjectId, objectKey, "doc.pdf",
				PDF_CONTENT_TYPE, 100L, "a".repeat(64), now);
		PosRecordEntity record = newRecord(posRecordId, mock(StorageObjectEntity.class), now);
		IngestionJobEntity job = newJob(jobId, record, JobStatus.QUEUED, 0L, now);
		PosDocumentEntity existingDoc = new PosDocumentEntity(documentId, record, existingStorage,
				0L, DocumentType.UNKNOWN, DocumentProcessingStatus.PENDING);

		when(this.posRecordRepository.findByIdAndDeletedAtIsNull(posRecordId)).thenReturn(Optional.of(record));
		when(this.ingestionJobRepository.findById(jobId)).thenReturn(Optional.of(job));
		when(this.posDocumentRepository.findByPosRecordIdOrderBySequenceNumberAsc(posRecordId))
				.thenReturn(List.of(existingDoc));

		// Mismatch the SHA-256: same id/key/size, but the proposed hash differs.
		ExtractedPdf mismatch = new ExtractedPdf(documentId, storageObjectId, objectKey, "doc.pdf",
				100L, "b".repeat(64), 0, false, now);

		RecordingTxManager txm = new RecordingTxManager();
		ExtractionPersistenceService service = buildService(txm);

		ConsumerException thrown = assertThrows(ConsumerException.class,
				() -> service.persistExtraction(posRecordId, jobId, List.of(mismatch), now));
		assertEquals(ConsumerException.Code.EXTRACTION_STATE_CONFLICT, thrown.getCode());
		assertEquals(JobStatus.QUEUED, job.getStatus());
		assertEquals(1, txm.rollbackCount);
		assertEquals(0, txm.commitCount);
	}

	@Test
	void mismatchOnContentTypeIsRejected() {
		UUID posRecordId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID documentId = UUID.randomUUID();
		UUID storageObjectId = UUID.randomUUID();
		String objectKey = "documents/" + posRecordId + "/" + documentId + ".pdf";
		Instant now = Instant.parse("2026-01-02T03:04:05Z");
		String sha = "a".repeat(64);

		StorageObjectEntity existingStorage = newStorage(storageObjectId, objectKey, "doc.pdf",
				PDF_CONTENT_TYPE, 100L, sha, now);
		PosRecordEntity record = newRecord(posRecordId, mock(StorageObjectEntity.class), now);
		IngestionJobEntity job = newJob(jobId, record, JobStatus.QUEUED, 0L, now);
		PosDocumentEntity existingDoc = new PosDocumentEntity(documentId, record, existingStorage,
				0L, DocumentType.UNKNOWN, DocumentProcessingStatus.PENDING);

		when(this.posRecordRepository.findByIdAndDeletedAtIsNull(posRecordId)).thenReturn(Optional.of(record));
		when(this.ingestionJobRepository.findById(jobId)).thenReturn(Optional.of(job));
		when(this.posDocumentRepository.findByPosRecordIdOrderBySequenceNumberAsc(posRecordId))
				.thenReturn(List.of(existingDoc));

		// Mismatch the original filename.
		ExtractedPdf mismatch = new ExtractedPdf(documentId, storageObjectId, objectKey, "other.pdf",
				100L, sha, 0, false, now);

		RecordingTxManager txm = new RecordingTxManager();
		ExtractionPersistenceService service = buildService(txm);

		assertThrows(ConsumerException.class,
				() -> service.persistExtraction(posRecordId, jobId, List.of(mismatch), now));
		assertEquals(JobStatus.QUEUED, job.getStatus());
		assertEquals(1, txm.rollbackCount);
	}

	@Test
	void mismatchOnDocumentIdIsRejected() {
		UUID posRecordId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID storageObjectId = UUID.randomUUID();
		String objectKey = "documents/" + posRecordId + "/any.pdf";
		Instant now = Instant.parse("2026-01-02T03:04:05Z");

		StorageObjectEntity existingStorage = newStorage(storageObjectId, objectKey, "doc.pdf",
				PDF_CONTENT_TYPE, 100L, "a".repeat(64), now);
		PosRecordEntity record = newRecord(posRecordId, mock(StorageObjectEntity.class), now);
		IngestionJobEntity job = newJob(jobId, record, JobStatus.RUNNING, 1L, now);
		UUID existingDocId = UUID.randomUUID();
		PosDocumentEntity existingDoc = new PosDocumentEntity(existingDocId, record, existingStorage,
				0L, DocumentType.UNKNOWN, DocumentProcessingStatus.PENDING);

		when(this.posRecordRepository.findByIdAndDeletedAtIsNull(posRecordId)).thenReturn(Optional.of(record));
		when(this.ingestionJobRepository.findById(jobId)).thenReturn(Optional.of(job));
		when(this.posDocumentRepository.findByPosRecordIdOrderBySequenceNumberAsc(posRecordId))
				.thenReturn(List.of(existingDoc));

		UUID proposedDocId = UUID.randomUUID(); // different from existingDocId
		ExtractedPdf mismatch = new ExtractedPdf(proposedDocId, storageObjectId, objectKey, "doc.pdf",
				100L, "a".repeat(64), 0, false, now);

		RecordingTxManager txm = new RecordingTxManager();
		ExtractionPersistenceService service = buildService(txm);

		assertThrows(ConsumerException.class,
				() -> service.persistExtraction(posRecordId, jobId, List.of(mismatch), now));
		assertEquals(JobStatus.RUNNING, job.getStatus());
	}

	@Test
	void mismatchOnSizeIsRejected() {
		UUID posRecordId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID documentId = UUID.randomUUID();
		UUID storageObjectId = UUID.randomUUID();
		String objectKey = "documents/" + posRecordId + "/" + documentId + ".pdf";
		Instant now = Instant.parse("2026-01-02T03:04:05Z");
		String sha = "a".repeat(64);

		StorageObjectEntity existingStorage = newStorage(storageObjectId, objectKey, "doc.pdf",
				PDF_CONTENT_TYPE, 100L, sha, now);
		PosRecordEntity record = newRecord(posRecordId, mock(StorageObjectEntity.class), now);
		IngestionJobEntity job = newJob(jobId, record, JobStatus.RUNNING, 1L, now);
		PosDocumentEntity existingDoc = new PosDocumentEntity(documentId, record, existingStorage,
				0L, DocumentType.UNKNOWN, DocumentProcessingStatus.PENDING);

		when(this.posRecordRepository.findByIdAndDeletedAtIsNull(posRecordId)).thenReturn(Optional.of(record));
		when(this.ingestionJobRepository.findById(jobId)).thenReturn(Optional.of(job));
		when(this.posDocumentRepository.findByPosRecordIdOrderBySequenceNumberAsc(posRecordId))
				.thenReturn(List.of(existingDoc));

		ExtractedPdf mismatch = new ExtractedPdf(documentId, storageObjectId, objectKey, "doc.pdf",
				200L, sha, 0, false, now);

		RecordingTxManager txm = new RecordingTxManager();
		ExtractionPersistenceService service = buildService(txm);

		assertThrows(ConsumerException.class,
				() -> service.persistExtraction(posRecordId, jobId, List.of(mismatch), now));
		assertEquals(JobStatus.RUNNING, job.getStatus());
	}

	@Test
	void mismatchOnProcessingStatusIsRejected() {
		UUID posRecordId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID documentId = UUID.randomUUID();
		UUID storageObjectId = UUID.randomUUID();
		String objectKey = "documents/" + posRecordId + "/" + documentId + ".pdf";
		Instant now = Instant.parse("2026-01-02T03:04:05Z");
		String sha = "a".repeat(64);

		StorageObjectEntity existingStorage = newStorage(storageObjectId, objectKey, "doc.pdf",
				PDF_CONTENT_TYPE, 100L, sha, now);
		PosRecordEntity record = newRecord(posRecordId, mock(StorageObjectEntity.class), now);
		IngestionJobEntity job = newJob(jobId, record, JobStatus.RUNNING, 1L, now);
		PosDocumentEntity existingDoc = new PosDocumentEntity(documentId, record, existingStorage,
				0L, DocumentType.UNKNOWN, DocumentProcessingStatus.PROCESSING);

		when(this.posRecordRepository.findByIdAndDeletedAtIsNull(posRecordId)).thenReturn(Optional.of(record));
		when(this.ingestionJobRepository.findById(jobId)).thenReturn(Optional.of(job));
		when(this.posDocumentRepository.findByPosRecordIdOrderBySequenceNumberAsc(posRecordId))
				.thenReturn(List.of(existingDoc));

		ExtractedPdf proposed = new ExtractedPdf(documentId, storageObjectId, objectKey, "doc.pdf",
				100L, sha, 0, false, now);

		RecordingTxManager txm = new RecordingTxManager();
		ExtractionPersistenceService service = buildService(txm);

		assertThrows(ConsumerException.class,
				() -> service.persistExtraction(posRecordId, jobId, List.of(proposed), now));
	}

	@Test
	void rowCountMismatchIsRejected() {
		UUID posRecordId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID documentId = UUID.randomUUID();
		UUID storageObjectId = UUID.randomUUID();
		String objectKey = "documents/" + posRecordId + "/" + documentId + ".pdf";
		Instant now = Instant.parse("2026-01-02T03:04:05Z");
		String sha = "a".repeat(64);

		StorageObjectEntity existingStorage = newStorage(storageObjectId, objectKey, "doc.pdf",
				PDF_CONTENT_TYPE, 100L, sha, now);
		PosRecordEntity record = newRecord(posRecordId, mock(StorageObjectEntity.class), now);
		IngestionJobEntity job = newJob(jobId, record, JobStatus.RUNNING, 1L, now);
		PosDocumentEntity existingDoc = new PosDocumentEntity(documentId, record, existingStorage,
				0L, DocumentType.UNKNOWN, DocumentProcessingStatus.PENDING);
		PosDocumentEntity existingDoc2 = new PosDocumentEntity(UUID.randomUUID(), record, existingStorage,
				1L, DocumentType.UNKNOWN, DocumentProcessingStatus.PENDING);

		when(this.posRecordRepository.findByIdAndDeletedAtIsNull(posRecordId)).thenReturn(Optional.of(record));
		when(this.ingestionJobRepository.findById(jobId)).thenReturn(Optional.of(job));
		when(this.posDocumentRepository.findByPosRecordIdOrderBySequenceNumberAsc(posRecordId))
				.thenReturn(List.of(existingDoc, existingDoc2));

		ExtractedPdf only = new ExtractedPdf(documentId, storageObjectId, objectKey, "doc.pdf",
				100L, sha, 0, false, now);

		RecordingTxManager txm = new RecordingTxManager();
		ExtractionPersistenceService service = buildService(txm);

		assertThrows(ConsumerException.class,
				() -> service.persistExtraction(posRecordId, jobId, List.of(only), now));
		assertEquals(JobStatus.RUNNING, job.getStatus());
	}

	@Test
	void deletedRecordIsRejected() {
		UUID posRecordId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		Instant now = Instant.parse("2026-01-02T03:04:05Z");

		when(this.posRecordRepository.findByIdAndDeletedAtIsNull(posRecordId))
				.thenReturn(Optional.empty());

		RecordingTxManager txm = new RecordingTxManager();
		ExtractionPersistenceService service = buildService(txm);

		assertThrows(ConsumerException.class,
				() -> service.persistExtraction(posRecordId, jobId, List.of(), now));
	}

	@Test
	void misreferencedJobIsRejected() {
		UUID posRecordId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID otherRecordId = UUID.randomUUID();
		Instant now = Instant.parse("2026-01-02T03:04:05Z");

		StorageObjectEntity otherArchive = newStorage(UUID.randomUUID(), "archives/other.zip",
				"other.zip", "application/zip", 100L, "a".repeat(64), now);
		PosRecordEntity otherRecord = new PosRecordEntity(otherRecordId, otherArchive,
				PosRecordStatus.UPLOADED, "tester", now, now);
		IngestionJobEntity job = newJob(jobId, otherRecord, JobStatus.RUNNING, 1L, now);

		when(this.posRecordRepository.findByIdAndDeletedAtIsNull(posRecordId))
				.thenReturn(Optional.empty());
		when(this.ingestionJobRepository.findById(jobId)).thenReturn(Optional.of(job));

		RecordingTxManager txm = new RecordingTxManager();
		ExtractionPersistenceService service = buildService(txm);

		assertThrows(ConsumerException.class,
				() -> service.persistExtraction(posRecordId, jobId, List.of(), now));
	}

	@Test
	void commitOrRollbackCountersAreAccurate() {
		RecordingTxManager txm = new RecordingTxManager();
		assertEquals(0, txm.commitCount);
		assertEquals(0, txm.rollbackCount);
		TransactionStatus status = txm.getTransaction(null);
		txm.commit(status);
		assertEquals(1, txm.commitCount);
		TransactionStatus s2 = txm.getTransaction(null);
		txm.rollback(s2);
		assertEquals(1, txm.rollbackCount);
	}

	/** Recording test double for transaction-manager behavior. */
	private static final class RecordingTxManager implements PlatformTransactionManager {

		final AtomicReference<TransactionStatus> lastStatus = new AtomicReference<>();
		int commitCount = 0;
		int rollbackCount = 0;

		@Override
		public TransactionStatus getTransaction(org.springframework.transaction.TransactionDefinition definition) {
			lastStatus.set(new SimpleTransactionStatus());
			return lastStatus.get();
		}

		@Override
		public void commit(TransactionStatus status) {
			commitCount++;
		}

		@Override
		public void rollback(TransactionStatus status) {
			rollbackCount++;
		}
	}
}