package horse.sumomo.pos_doc_backend.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import horse.sumomo.pos_doc_backend.persistence.entity.PosRecordEntity;
import horse.sumomo.pos_doc_backend.persistence.entity.StorageObjectEntity;
import horse.sumomo.pos_doc_backend.persistence.model.PosRecordStatus;
import horse.sumomo.pos_doc_backend.persistence.repository.PosRecordRepository;
import horse.sumomo.pos_doc_backend.persistence.repository.StorageObjectRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Persistence integration tests for {@link PosRecordRepository} using the
 * real Spring application context, the real repositories, real JPA, and a
 * unique temporary SQLite database. Constraint violations are proven by
 * flushing inside the assertion: a test that never flushes does not prove
 * SQLite enforced the constraint.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PosRecordRepositoryIntegrationTest {

	private static final String TEST_SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

	@Autowired
	private PosRecordRepository posRecordRepository;

	@Autowired
	private StorageObjectRepository storageObjectRepository;

	@Autowired
	private EntityManager entityManager;

	@DynamicPropertySource
	static void sqliteUrl(DynamicPropertyRegistry registry) throws Exception {
		Path dbFile = Files.createTempFile("pos-doc-posrecord-test", ".db");
		dbFile.toFile().deleteOnExit();
		Path.of(dbFile.toString() + "-wal").toFile().deleteOnExit();
		Path.of(dbFile.toString() + "-shm").toFile().deleteOnExit();
		registry.add("SQLITE_URL", () -> "jdbc:sqlite:" + dbFile);
	}

	@Test
	void recordSavesAndLoadsUnchanged() {
		StorageObjectEntity archive = saveArchive("posrecord-roundtrip/archive");
		Instant uploadedAt = Instant.ofEpochMilli(1_700_000_000_000L);
		Instant updatedAt = Instant.ofEpochMilli(1_700_000_100_000L);

		PosRecordEntity record = new PosRecordEntity(UUID.randomUUID(), archive,
				PosRecordStatus.UPLOADED, "tester-subject", uploadedAt, updatedAt);
		record.setErefNumber(" EREF-2026 001 ");
		record.setPolicyNumber("POLICY-TEST-001");
		record.setPolicyholderName("  Jane   TAN ");
		record.setConsultantName("Consultant Test");
		record.setPolicyCreateDate(LocalDate.of(2026, 8, 1));

		this.posRecordRepository.saveAndFlush(record);

		Optional<PosRecordEntity> loaded = this.posRecordRepository
				.findByIdAndDeletedAtIsNull(record.getId());
		assertTrue(loaded.isPresent());
		PosRecordEntity l = loaded.get();
		assertEquals(record.getId(), l.getId());
		assertEquals(PosRecordStatus.UPLOADED, l.getStatus());
		// The display column stores the value exactly as provided; only the
		// normalized column is canonicalized.
		assertEquals(" EREF-2026 001 ", l.getErefNumber());
		assertEquals("EREF2026001", l.getErefNumberNormalized());
		assertEquals("POLICY-TEST-001", l.getPolicyNumber());
		assertEquals("POLICYTEST001", l.getPolicyNumberNormalized());
		assertEquals("  Jane   TAN ", l.getPolicyholderName());
		assertEquals("jane tan", l.getPolicyholderNameNormalized());
		assertEquals("Consultant Test", l.getConsultantName());
		assertEquals(LocalDate.of(2026, 8, 1), l.getPolicyCreateDate());
		assertEquals("tester-subject", l.getUploadedBy());
		assertEquals(uploadedAt, l.getUploadedAt());
		assertEquals(updatedAt, l.getUpdatedAt());
		assertEquals(0, l.getVersion());
		assertEquals(archive.getId(), l.getSourceArchive().getId());
	}

	@Test
	void erefLookupUsesNormalizedValue() {
		StorageObjectEntity archive = saveArchive("posrecord-eref-lookup/archive");
		PosRecordEntity record = new PosRecordEntity(UUID.randomUUID(), archive,
				PosRecordStatus.UPLOADED, "tester-subject",
				Instant.ofEpochMilli(1_700_000_000_000L), Instant.ofEpochMilli(1_700_000_000_000L));
		record.setErefNumber("  eref-999888  ");
		this.posRecordRepository.saveAndFlush(record);

		Optional<PosRecordEntity> byNormalized = this.posRecordRepository
				.findByErefNumberNormalizedAndDeletedAtIsNull("EREF999888");
		assertTrue(byNormalized.isPresent());
		assertEquals(record.getId(), byNormalized.get().getId());

		assertFalse(this.posRecordRepository
				.existsByErefNumberNormalizedAndDeletedAtIsNull("EREF-999888"));
		assertTrue(this.posRecordRepository
				.existsByErefNumberNormalizedAndDeletedAtIsNull("EREF999888"));
	}

	@Test
	void policyNumberLookupUsesNormalizedValue() {
		StorageObjectEntity archive = saveArchive("posrecord-policy-lookup/archive");
		PosRecordEntity record = new PosRecordEntity(UUID.randomUUID(), archive,
				PosRecordStatus.UPLOADED, "tester-subject",
				Instant.ofEpochMilli(1_700_000_000_000L), Instant.ofEpochMilli(1_700_000_000_000L));
		record.setPolicyNumber("  Policy/777  ");
		this.posRecordRepository.saveAndFlush(record);

		Optional<PosRecordEntity> byNormalized = this.posRecordRepository
				.findByPolicyNumberNormalizedAndDeletedAtIsNull("POLICY777");
		assertTrue(byNormalized.isPresent());
		assertEquals(record.getId(), byNormalized.get().getId());

		assertFalse(this.posRecordRepository
				.existsByPolicyNumberNormalizedAndDeletedAtIsNull("POLICY-777"));
		assertTrue(this.posRecordRepository
				.existsByPolicyNumberNormalizedAndDeletedAtIsNull("POLICY777"));
	}

	@Test
	void twoActiveRecordsWithEquivalentErefsViolateUniqueConstraint() {
		StorageObjectEntity archive1 = saveArchive("posrecord-uq-eref/a1");
		StorageObjectEntity archive2 = saveArchive("posrecord-uq-eref/a2");
		Instant now = Instant.ofEpochMilli(1_700_000_000_000L);

		PosRecordEntity record1 = new PosRecordEntity(UUID.randomUUID(), archive1,
				PosRecordStatus.UPLOADED, "tester-subject", now, now);
		record1.setErefNumber("EREF-UNIQUE-1");
		this.posRecordRepository.saveAndFlush(record1);

		PosRecordEntity record2 = new PosRecordEntity(UUID.randomUUID(), archive2,
				PosRecordStatus.UPLOADED, "tester-subject", now, now);
		record2.setErefNumber("  eref unique 1 ");
		assertSqliteConstraintViolation(() -> this.posRecordRepository.saveAndFlush(record2),
				"UNIQUE constraint failed: pos_record.eref_number_normalized");
	}

	@Test
	void twoActiveRecordsWithEquivalentPoliciesViolateUniqueConstraint() {
		StorageObjectEntity archive1 = saveArchive("posrecord-uq-policy/a1");
		StorageObjectEntity archive2 = saveArchive("posrecord-uq-policy/a2");
		Instant now = Instant.ofEpochMilli(1_700_000_000_000L);

		PosRecordEntity record1 = new PosRecordEntity(UUID.randomUUID(), archive1,
				PosRecordStatus.UPLOADED, "tester-subject", now, now);
		record1.setPolicyNumber("POLICY-DUP-1");
		this.posRecordRepository.saveAndFlush(record1);

		PosRecordEntity record2 = new PosRecordEntity(UUID.randomUUID(), archive2,
				PosRecordStatus.UPLOADED, "tester-subject", now, now);
		record2.setPolicyNumber("policy dup 1");
		assertSqliteConstraintViolation(() -> this.posRecordRepository.saveAndFlush(record2),
				"UNIQUE constraint failed: pos_record.policy_number_normalized");
	}

	@Test
	void multipleActiveRecordsMayHaveNullPolicyNumbers() {
		StorageObjectEntity archive1 = saveArchive("posrecord-null-policy/a1");
		StorageObjectEntity archive2 = saveArchive("posrecord-null-policy/a2");
		Instant now = Instant.ofEpochMilli(1_700_000_000_000L);

		PosRecordEntity record1 = new PosRecordEntity(UUID.randomUUID(), archive1,
				PosRecordStatus.UPLOADED, "tester-subject", now, now);
		record1.setErefNumber("EREF-NULLPOLICY-1");
		this.posRecordRepository.saveAndFlush(record1);

		PosRecordEntity record2 = new PosRecordEntity(UUID.randomUUID(), archive2,
				PosRecordStatus.UPLOADED, "tester-subject", now, now);
		record2.setErefNumber("EREF-NULLPOLICY-2");
		this.posRecordRepository.saveAndFlush(record2);

		assertTrue(this.posRecordRepository.findByIdAndDeletedAtIsNull(record1.getId()).isPresent());
		assertTrue(this.posRecordRepository.findByIdAndDeletedAtIsNull(record2.getId()).isPresent());
	}

	@Test
	void softDeletedRecordIsAbsentFromActiveLookupsButAvailableViaFindById() {
		StorageObjectEntity archive = saveArchive("posrecord-softdelete/archive");
		Instant now = Instant.ofEpochMilli(1_700_000_000_000L);

		PosRecordEntity record = new PosRecordEntity(UUID.randomUUID(), archive,
				PosRecordStatus.UPLOADED, "tester-subject", now, now);
		record.setErefNumber("EREF-SOFTDEL-1");
		record.setPolicyNumber("POLICY-SOFTDEL-1");
		this.posRecordRepository.saveAndFlush(record);

		record.markDeleted(Instant.ofEpochMilli(1_700_000_500_000L));
		this.posRecordRepository.saveAndFlush(record);

		assertFalse(this.posRecordRepository.findByIdAndDeletedAtIsNull(record.getId()).isPresent());
		assertFalse(this.posRecordRepository
				.existsByErefNumberNormalizedAndDeletedAtIsNull("EREF-SOFTDEL1"));
		assertFalse(this.posRecordRepository
				.existsByPolicyNumberNormalizedAndDeletedAtIsNull("POLICY-SOFTDEL1"));

		Optional<PosRecordEntity> raw = this.posRecordRepository.findById(record.getId());
		assertTrue(raw.isPresent());
		assertNotNull(raw.get().getDeletedAt());
	}

	@Test
	void valuesCanBeReusedByNewActiveRecordAfterSoftDelete() {
		StorageObjectEntity archiveOld = saveArchive("posrecord-reuse/a-old");
		StorageObjectEntity archiveNew = saveArchive("posrecord-reuse/a-new");
		Instant now = Instant.ofEpochMilli(1_700_000_000_000L);

		PosRecordEntity oldRecord = new PosRecordEntity(UUID.randomUUID(), archiveOld,
				PosRecordStatus.UPLOADED, "tester-subject", now, now);
		oldRecord.setErefNumber("EREF-REUSE-1");
		oldRecord.setPolicyNumber("POLICY-REUSE-1");
		this.posRecordRepository.saveAndFlush(oldRecord);

		oldRecord.markDeleted(Instant.ofEpochMilli(1_700_000_500_000L));
		this.posRecordRepository.saveAndFlush(oldRecord);

		PosRecordEntity newRecord = new PosRecordEntity(UUID.randomUUID(), archiveNew,
				PosRecordStatus.UPLOADED, "tester-subject", now, now);
		newRecord.setErefNumber("EREF-REUSE-1");
		newRecord.setPolicyNumber("POLICY-REUSE-1");
		this.posRecordRepository.saveAndFlush(newRecord);

		Optional<PosRecordEntity> active = this.posRecordRepository
				.findByErefNumberNormalizedAndDeletedAtIsNull("EREFREUSE1");
		assertTrue(active.isPresent());
		assertEquals(newRecord.getId(), active.get().getId());
	}

	@Test
	void updatingAnEntityIncrementsItsVersion() {
		StorageObjectEntity archive = saveArchive("posrecord-version/archive");
		Instant now = Instant.ofEpochMilli(1_700_000_000_000L);

		PosRecordEntity record = new PosRecordEntity(UUID.randomUUID(), archive,
				PosRecordStatus.UPLOADED, "tester-subject", now, now);
		// saveAndFlush merges the (detached) entity, so the version must be
		// read from the managed instance it returns.
		record = this.posRecordRepository.saveAndFlush(record);
		assertEquals(0, record.getVersion());

		record.setStatus(PosRecordStatus.PROCESSING);
		record = this.posRecordRepository.saveAndFlush(record);
		assertEquals(1, record.getVersion());

		record.setStatus(PosRecordStatus.COMPLETED);
		record = this.posRecordRepository.saveAndFlush(record);
		assertEquals(2, record.getVersion());
	}

	@Test
	void conflictingWriteOnStaleCopyThrowsOptimisticLockingException() {
		StorageObjectEntity archive = saveArchive("posrecord-optlock/archive");
		Instant now = Instant.ofEpochMilli(1_700_000_000_000L);

		PosRecordEntity record = new PosRecordEntity(UUID.randomUUID(), archive,
				PosRecordStatus.UPLOADED, "tester-subject", now, now);
		this.posRecordRepository.saveAndFlush(record);
		long firstVersion = record.getVersion();

		// Load two independent copies of the same row, then clear the
		// persistence context so each later save is an update of a detached
		// copy holding its own (now possibly stale) version.
		PosRecordEntity copy1 = this.posRecordRepository.findById(record.getId()).orElseThrow();
		PosRecordEntity copy2 = this.posRecordRepository.findById(record.getId()).orElseThrow();
		this.entityManager.clear();

		copy1.setStatus(PosRecordStatus.PROCESSING);
		this.posRecordRepository.saveAndFlush(copy1);

		copy2.setStatus(PosRecordStatus.FAILED);
		assertThrows(OptimisticLockingFailureException.class,
				() -> this.posRecordRepository.saveAndFlush(copy2));

		this.entityManager.clear();
		PosRecordEntity winner = this.posRecordRepository.findById(record.getId()).orElseThrow();
		assertEquals(PosRecordStatus.PROCESSING, winner.getStatus());
		assertEquals(firstVersion + 1, winner.getVersion());
	}

	private StorageObjectEntity saveArchive(String objectKey) {
		StorageObjectEntity archive = new StorageObjectEntity(
				UUID.randomUUID(), objectKey, "archive.zip", "application/zip", 128L, TEST_SHA,
				Instant.ofEpochMilli(1_700_000_000_000L));
		return this.storageObjectRepository.saveAndFlush(archive);
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
