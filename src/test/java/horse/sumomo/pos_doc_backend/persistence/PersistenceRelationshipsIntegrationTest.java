package horse.sumomo.pos_doc_backend.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Persistence integration tests proving the relationship rules between
 * storage objects, POS records, documents, and ingestion jobs on a unique
 * temporary SQLite database.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PersistenceRelationshipsIntegrationTest {

	private static final String TEST_SHA = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";

	@Autowired
	private StorageObjectRepository storageObjectRepository;

	@Autowired
	private PosRecordRepository posRecordRepository;

	@Autowired
	private PosDocumentRepository posDocumentRepository;

	@Autowired
	private IngestionJobRepository ingestionJobRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@DynamicPropertySource
	static void sqliteUrl(DynamicPropertyRegistry registry) throws Exception {
		Path dbFile = Files.createTempFile("pos-doc-relationships-test", ".db");
		dbFile.toFile().deleteOnExit();
		Path.of(dbFile.toString() + "-wal").toFile().deleteOnExit();
		Path.of(dbFile.toString() + "-shm").toFile().deleteOnExit();
		registry.add("SQLITE_URL", () -> "jdbc:sqlite:" + dbFile);
	}

	@Test
	void oneArchiveStorageObjectCanAttachToOnePosRecord() {
		StorageObjectEntity archive = saveArchive("rel-archive-only/archive");
		PosRecordEntity record = saveRecord(archive);
		assertEquals(archive.getId(), record.getSourceArchive().getId());
	}

	@Test
	void multipleDocumentsCanBelongToOnePosRecordAndLoadInSequenceOrder() {
		StorageObjectEntity archive = saveArchive("rel-docs/archive");
		PosRecordEntity record = saveRecord(archive);

		StorageObjectEntity pdf2 = saveDocumentObject("rel-docs/doc-2.pdf");
		StorageObjectEntity pdf1 = saveDocumentObject("rel-docs/doc-1.pdf");
		StorageObjectEntity pdf3 = saveDocumentObject("rel-docs/doc-3.pdf");

		// Insert out of order to prove ordering comes from the query, not
		// from insertion order.
		this.posDocumentRepository.saveAndFlush(new PosDocumentEntity(UUID.randomUUID(), record, pdf2, 2,
				DocumentType.OTHER, DocumentProcessingStatus.PENDING));
		this.posDocumentRepository.saveAndFlush(new PosDocumentEntity(UUID.randomUUID(), record, pdf1, 1,
				DocumentType.FA_PRUPLANNER_REPORT, DocumentProcessingStatus.PENDING));
		this.posDocumentRepository.saveAndFlush(new PosDocumentEntity(UUID.randomUUID(), record, pdf3, 3,
				DocumentType.UNKNOWN, DocumentProcessingStatus.SKIPPED));

		List<PosDocumentEntity> docs = this.posDocumentRepository
				.findByPosRecordIdOrderBySequenceNumberAsc(record.getId());
		assertEquals(3, docs.size());
		assertEquals(1, docs.get(0).getSequenceNumber());
		assertEquals(2, docs.get(1).getSequenceNumber());
		assertEquals(3, docs.get(2).getSequenceNumber());
	}

	@Test
	void multipleIngestionJobsCanBelongToOnePosRecordAndLoadInCreatedAtOrder() {
		StorageObjectEntity archive = saveArchive("rel-jobs/archive");
		PosRecordEntity record = saveRecord(archive);

		// Insert out of order to prove ordering comes from the query, not
		// from insertion order.
		this.ingestionJobRepository.saveAndFlush(new IngestionJobEntity(UUID.randomUUID(), record,
				JobStatus.QUEUED, 0, Instant.ofEpochMilli(3_000L)));
		this.ingestionJobRepository.saveAndFlush(new IngestionJobEntity(UUID.randomUUID(), record,
				JobStatus.QUEUED, 0, Instant.ofEpochMilli(1_000L)));
		this.ingestionJobRepository.saveAndFlush(new IngestionJobEntity(UUID.randomUUID(), record,
				JobStatus.QUEUED, 0, Instant.ofEpochMilli(2_000L)));

		List<IngestionJobEntity> jobs = this.ingestionJobRepository
				.findByPosRecordIdOrderByCreatedAtAsc(record.getId());
		assertEquals(3, jobs.size());
		List<Instant> createdAt = jobs.stream()
				.map(IngestionJobEntity::getCreatedAt)
				.toList();
		// The query returns ascending createdAt order regardless of the
		// out-of-order insertion sequence above.
		assertEquals(List.of(Instant.ofEpochMilli(1_000L), Instant.ofEpochMilli(2_000L), Instant.ofEpochMilli(3_000L)),
				createdAt);
		// The ascending sequence is also strictly non-decreasing.
		for (int i = 1; i < createdAt.size(); i++) {
			assertTrue(!createdAt.get(i - 1).isAfter(createdAt.get(i)));
		}
	}

	@Test
	void sameArchiveCannotBeAttachedToTwoPosRecords() {
		StorageObjectEntity archive = saveArchive("rel-archive-reuse/archive");
		saveRecord(archive);

		PosRecordEntity second = new PosRecordEntity(UUID.randomUUID(), archive,
				PosRecordStatus.UPLOADED, "tester-subject",
				Instant.ofEpochMilli(1_700_000_000_000L), Instant.ofEpochMilli(1_700_000_000_000L));
		assertSqliteConstraintViolation(() -> this.posRecordRepository.saveAndFlush(second),
				"UNIQUE constraint failed: pos_record.source_archive_id");
	}

	@Test
	void samePdfStorageObjectCannotBackTwoDocumentRows() {
		StorageObjectEntity archive = saveArchive("rel-pdf-reuse/archive");
		PosRecordEntity record = saveRecord(archive);
		StorageObjectEntity pdf = saveDocumentObject("rel-pdf-reuse/doc.pdf");

		this.posDocumentRepository.saveAndFlush(new PosDocumentEntity(UUID.randomUUID(), record, pdf, 1,
				DocumentType.OTHER, DocumentProcessingStatus.PENDING));

		PosDocumentEntity duplicate = new PosDocumentEntity(UUID.randomUUID(), record, pdf, 2,
				DocumentType.OTHER, DocumentProcessingStatus.PENDING);
		assertSqliteConstraintViolation(() -> this.posDocumentRepository.saveAndFlush(duplicate),
				"UNIQUE constraint failed: pos_document.storage_object_id");
	}

	@Test
	void documentReferencingNonexistentPosRecordIsRejectedByForeignKey() {
		// A JPA entity always references a managed record, so the impossible
		// relationship is constructed with a raw SQL statement: this proves
		// SQLite's foreign-key constraint, not the JPA layer.
		UUID missingRecordId = UUID.randomUUID();
		StorageObjectEntity pdf = saveDocumentObject("rel-fk-orphan/doc.pdf");

		assertSqliteConstraintViolation(() -> this.jdbcTemplate.update(
				"INSERT INTO pos_document (id, pos_record_id, storage_object_id, sequence_number, "
						+ "document_type, processing_status) VALUES (?, ?, ?, 1, 'OTHER', 'PENDING')",
				UUID.randomUUID().toString(), missingRecordId.toString(), pdf.getId().toString()),
				"A foreign key constraint failed");
	}

	@Test
	void deletingReferencedStorageMetadataIsRejectedRatherThanCascading() {
		StorageObjectEntity archive = saveArchive("rel-referenced-archive/archive");
		PosRecordEntity record = saveRecord(archive);
		StorageObjectEntity pdf = saveDocumentObject("rel-referenced-archive/doc.pdf");
		this.posDocumentRepository.saveAndFlush(new PosDocumentEntity(UUID.randomUUID(), record, pdf, 1,
				DocumentType.OTHER, DocumentProcessingStatus.PENDING));

		// Both the source archive and the document PDF are now referenced;
		// RESTRICT must reject each deletion.
		assertSqliteConstraintViolation(
				() -> this.storageObjectRepository.deleteById(archive.getId()),
				"FOREIGN KEY constraint failed");
		assertSqliteConstraintViolation(
				() -> this.storageObjectRepository.deleteById(pdf.getId()),
				"FOREIGN KEY constraint failed");

		// And nothing was cascaded: the record and its document still exist.
		assertTrue(this.posRecordRepository.findById(record.getId()).isPresent());
		assertEquals(1, this.posDocumentRepository.findByPosRecordIdOrderBySequenceNumberAsc(record.getId()).size());
	}

	private StorageObjectEntity saveArchive(String objectKey) {
		StorageObjectEntity archive = new StorageObjectEntity(UUID.randomUUID(), objectKey,
				"archive.zip", "application/zip", 256L, TEST_SHA, Instant.ofEpochMilli(1_700_000_000_000L));
		return this.storageObjectRepository.saveAndFlush(archive);
	}

	private StorageObjectEntity saveDocumentObject(String objectKey) {
		StorageObjectEntity pdf = new StorageObjectEntity(UUID.randomUUID(), objectKey,
				"document.pdf", "application/pdf", 1024L, TEST_SHA, Instant.ofEpochMilli(1_700_000_000_000L));
		return this.storageObjectRepository.saveAndFlush(pdf);
	}

	private PosRecordEntity saveRecord(StorageObjectEntity archive) {
		PosRecordEntity record = new PosRecordEntity(UUID.randomUUID(), archive,
				PosRecordStatus.UPLOADED, "tester-subject",
				Instant.ofEpochMilli(1_700_000_000_000L), Instant.ofEpochMilli(1_700_000_000_000L));
		return this.posRecordRepository.saveAndFlush(record);
	}

	/**
	 * Runs the action and proves SQLite itself rejected the write. With this
	 * Hibernate dialect + Xerial driver combination, constraint violations
	 * surface as JpaSystemException or UncategorizedSQLException (the driver
	 * reports a null SQL state, so Spring cannot classify them as
	 * DataIntegrityViolationException); asserting on the base
	 * {@link DataAccessException} plus the concrete SQLite constraint message
	 * proves the database enforced the constraint.
	 */
	private static void assertSqliteConstraintViolation(Runnable action, String expectedConstraintMessage) {
		DataAccessException failure = assertThrows(DataAccessException.class, action::run);
		String chain = exceptionChainMessage(failure);
		assertTrue(chain.contains(expectedConstraintMessage),
				"expected a SQLite constraint violation containing <" + expectedConstraintMessage
						+ "> but the failure chain was: " + chain);
	}

	private static String exceptionChainMessage(Throwable throwable) {
		StringBuilder messages = new StringBuilder();
		for (Throwable t = throwable; t != null; t = t.getCause()) {
			messages.append(t.getClass().getName()).append(": ").append(t.getMessage()).append("\n");
			if (t.getCause() == t) {
				break;
			}
		}
		return messages.toString();
	}

}
