package horse.sumomo.pos_doc_backend.review.testsupport;

import java.time.Instant;
import java.util.UUID;

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
 * Small factory for the persistence-backed review/search tests. Creates real,
 * synthetic rows (no PII) via the repositories and a unique per-class temporary
 * SQLite database. All identifiers are random; timestamps are deterministic.
 */
public final class ReviewTestFixtures {

	public static final String SHA =
			"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
	public static final Instant T0 = Instant.ofEpochMilli(1_700_000_000_000L);

	private ReviewTestFixtures() {
	}

	public static StorageObjectEntity saveArchive(StorageObjectRepository repo, String key) {
		return repo.saveAndFlush(new StorageObjectEntity(UUID.randomUUID(), key, "archive.zip",
				"application/zip", 128L, SHA, T0));
	}

	public static PosRecordEntity saveRecord(PosRecordRepository repo, StorageObjectEntity archive,
			PosRecordStatus status, Instant uploadedAt, Instant updatedAt) {
		return repo.saveAndFlush(new PosRecordEntity(UUID.randomUUID(), archive, status,
				"tester-subject", uploadedAt, updatedAt));
	}

	public static PosDocumentEntity saveDocument(PosDocumentRepository docRepo,
			StorageObjectRepository storageRepo, PosRecordEntity record, long sequence,
			DocumentProcessingStatus status) {
		StorageObjectEntity pdf = storageRepo.saveAndFlush(new StorageObjectEntity(UUID.randomUUID(),
				record.getId() + "/doc/" + sequence + "-" + UUID.randomUUID(), "document.pdf",
				"application/pdf", 64L, SHA, T0));
		return docRepo.saveAndFlush(new PosDocumentEntity(UUID.randomUUID(), record, pdf, sequence,
				DocumentType.OTHER, status));
	}

	public static IngestionJobEntity saveJob(IngestionJobRepository jobRepo, PosRecordEntity record,
			JobStatus status, long attemptCount, Instant createdAt) {
		IngestionJobEntity job = new IngestionJobEntity(UUID.randomUUID(), record, status, attemptCount,
				createdAt);
		if (status == JobStatus.COMPLETED) {
			job.complete(createdAt);
		}
		else if (status == JobStatus.FAILED) {
			job.fail(createdAt, "TEST_CODE", "test failure");
		}
		return jobRepo.saveAndFlush(job);
	}

	/**
	 * A reviewable, fully-extracted record: REVIEW_REQUIRED with all four display
	 * metadata values set, one COMPLETED document, and one COMPLETED job. This is
	 * the state the verification operation is designed to move to COMPLETED.
	 */
	public static PosRecordEntity saveVerifiableRecord(PosRecordRepository recordRepo,
			PosDocumentRepository docRepo, StorageObjectRepository storageRepo,
			IngestionJobRepository jobRepo, String eref, String policy, String holder, String consultant) {
		StorageObjectEntity archive = saveArchive(storageRepo, "archive-" + UUID.randomUUID());
		PosRecordEntity record = saveRecord(recordRepo, archive, PosRecordStatus.REVIEW_REQUIRED, T0, T0);
		if (eref != null) {
			record.setErefNumber(eref);
		}
		if (policy != null) {
			record.setPolicyNumber(policy);
		}
		if (holder != null) {
			record.setPolicyholderName(holder);
		}
		if (consultant != null) {
			record.setConsultantName(consultant);
		}
		recordRepo.saveAndFlush(record);
		saveDocument(docRepo, storageRepo, record, 0, DocumentProcessingStatus.COMPLETED);
		saveJob(jobRepo, record, JobStatus.COMPLETED, 1, T0);
		return record;
	}

}
