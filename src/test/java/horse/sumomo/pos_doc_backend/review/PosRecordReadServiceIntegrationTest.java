package horse.sumomo.pos_doc_backend.review;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.yourcompany.pos.api.model.PosRecord;

import horse.sumomo.pos_doc_backend.persistence.repository.PosRecordRepository;
import horse.sumomo.pos_doc_backend.persistence.repository.StorageObjectRepository;
import horse.sumomo.pos_doc_backend.review.testsupport.ReviewTestFixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Persistence integration tests for {@link PosRecordReadService} using a unique
 * temporary SQLite database, real repositories, and Flyway. Proves the detail
 * read maps every persisted field (including the source-archive summary and
 * version), that missing and soft-deleted records are 404, and that the mapping
 * works with Open-Session-in-View disabled (the mapping happens inside the
 * service's read-only transaction).
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PosRecordReadServiceIntegrationTest {

	@Autowired
	private PosRecordReadService readService;

	@Autowired
	private PosRecordRepository posRecordRepository;

	@Autowired
	private StorageObjectRepository storageObjectRepository;

	@DynamicPropertySource
	static void sqliteUrl(DynamicPropertyRegistry registry) throws Exception {
		Path dbFile = Files.createTempFile("pos-doc-review-read-test", ".db");
		dbFile.toFile().deleteOnExit();
		Path.of(dbFile.toString() + "-wal").toFile().deleteOnExit();
		Path.of(dbFile.toString() + "-shm").toFile().deleteOnExit();
		registry.add("SQLITE_URL", () -> "jdbc:sqlite:" + dbFile);
	}

	@Test
	void activeRecordMapsEveryFieldIncludingSourceArchiveAndVersion() {
		var archive = ReviewTestFixtures.saveArchive(this.storageObjectRepository, "archive-read-1");
		Instant uploadedAt = Instant.ofEpochMilli(1_700_000_111_111L);
		Instant updatedAt = Instant.ofEpochMilli(1_700_000_222_222L);
		var record = ReviewTestFixtures.saveRecord(this.posRecordRepository, archive,
				horse.sumomo.pos_doc_backend.persistence.model.PosRecordStatus.REVIEW_REQUIRED, uploadedAt, updatedAt);
		record.setErefNumber("EREF-2026-00123");
		record.setPolicyNumber("P12345678");
		record.setPolicyholderName("Jane   TAN");
		record.setConsultantName("Consultant One");
		record.setPolicyCreateDate(java.time.LocalDate.of(2026, 2, 1));
		record = this.posRecordRepository.saveAndFlush(record);
		long persistedVersion = record.getVersion();

		PosRecord dto = this.readService.getRecord(record.getId());

		assertEquals(record.getId(), dto.getId());
		// Display values are preserved exactly as provided; only the normalized
		// columns are canonicalized (and never exposed).
		assertEquals("EREF-2026-00123", dto.getErefNumber());
		assertEquals("P12345678", dto.getPolicyNumber());
		assertEquals("Jane   TAN", dto.getPolicyholderName());
		assertEquals("Consultant One", dto.getConsultantName());
		assertEquals(java.time.LocalDate.of(2026, 2, 1), dto.getPolicyCreateDate());
		assertEquals(com.yourcompany.pos.api.model.PosRecordStatus.REVIEW_REQUIRED, dto.getStatus());
		assertEquals(archive.getId(), dto.getSourceArchive().getId());
		assertEquals("archive.zip", dto.getSourceArchive().getOriginalFilename());
		assertEquals("application/zip", dto.getSourceArchive().getContentType());
		assertEquals(128L, dto.getSourceArchive().getByteSize());
		assertEquals(ReviewTestFixtures.SHA, dto.getSourceArchive().getSha256());
		assertEquals(OffsetDateTime.ofInstant(uploadedAt, ZoneOffset.UTC), dto.getUploadedAt());
		assertEquals(OffsetDateTime.ofInstant(updatedAt, ZoneOffset.UTC), dto.getUpdatedAt());
		assertEquals("tester-subject", dto.getUploadedBy());
		assertEquals(persistedVersion, dto.getVersion().longValue());
	}

	@Test
	void nullableMetadataMapsAsNull() {
		var archive = ReviewTestFixtures.saveArchive(this.storageObjectRepository, "archive-read-null");
		var record = ReviewTestFixtures.saveRecord(this.posRecordRepository, archive,
				horse.sumomo.pos_doc_backend.persistence.model.PosRecordStatus.UPLOADED,
				ReviewTestFixtures.T0, ReviewTestFixtures.T0);

		PosRecord dto = this.readService.getRecord(record.getId());

		assertNotNull(dto);
		assertEquals(record.getId(), dto.getId());
		org.junit.jupiter.api.Assertions.assertNull(dto.getErefNumber());
		org.junit.jupiter.api.Assertions.assertNull(dto.getPolicyNumber());
		org.junit.jupiter.api.Assertions.assertNull(dto.getPolicyholderName());
		org.junit.jupiter.api.Assertions.assertNull(dto.getConsultantName());
		org.junit.jupiter.api.Assertions.assertNull(dto.getPolicyCreateDate());
	}

	@Test
	void missingRecordIsNotFound() {
		UUID unknown = UUID.randomUUID();
		PosRecordApiException ex = assertThrows(PosRecordApiException.class,
				() -> this.readService.getRecord(unknown));
		assertEquals(PosRecordApiException.Code.POS_RECORD_NOT_FOUND, ex.getCode());
	}

	@Test
	void softDeletedRecordIsNotFound() {
		var archive = ReviewTestFixtures.saveArchive(this.storageObjectRepository, "archive-read-del");
		var record = ReviewTestFixtures.saveRecord(this.posRecordRepository, archive,
				horse.sumomo.pos_doc_backend.persistence.model.PosRecordStatus.COMPLETED,
				ReviewTestFixtures.T0, ReviewTestFixtures.T0);
		record.markDeleted(Instant.ofEpochMilli(1_700_000_900_000L));
		this.posRecordRepository.saveAndFlush(record);

		PosRecordApiException ex = assertThrows(PosRecordApiException.class,
				() -> this.readService.getRecord(record.getId()));
		assertEquals(PosRecordApiException.Code.POS_RECORD_NOT_FOUND, ex.getCode());
	}

	@Test
	void sourceArchiveIsLoadedWithinTheTransactionNotAfterItCloses() {
		// OSIV is disabled in this context. If the mapping depended on a session
		// open outside the service's transaction this would throw a
		// LazyInitializationException; a clean 200 DTO proves the association is
		// resolved inside the read-only transaction.
		var archive = ReviewTestFixtures.saveArchive(this.storageObjectRepository, "archive-read-osiv");
		var record = ReviewTestFixtures.saveRecord(this.posRecordRepository, archive,
				horse.sumomo.pos_doc_backend.persistence.model.PosRecordStatus.REVIEW_REQUIRED,
				ReviewTestFixtures.T0, ReviewTestFixtures.T0);
		record.setErefNumber("EREF-OSIV-1");
		this.posRecordRepository.saveAndFlush(record);

		PosRecord dto = this.readService.getRecord(record.getId());
		assertTrue(dto.getSourceArchive().getOriginalFilename().equals("archive.zip"));
	}

}
