package horse.sumomo.pos_doc_backend.review;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.yourcompany.pos.api.model.PosRecord;
import com.yourcompany.pos.api.model.PosRecordPatch;

import horse.sumomo.pos_doc_backend.ingestion.application.IntakeException;
import horse.sumomo.pos_doc_backend.ingestion.application.PosDocumentListService;
import horse.sumomo.pos_doc_backend.persistence.entity.IngestionJobEntity;
import horse.sumomo.pos_doc_backend.persistence.entity.PosDocumentEntity;
import horse.sumomo.pos_doc_backend.persistence.entity.PosRecordEntity;
import horse.sumomo.pos_doc_backend.persistence.model.DocumentProcessingStatus;
import horse.sumomo.pos_doc_backend.persistence.model.JobStatus;
import horse.sumomo.pos_doc_backend.persistence.model.PosRecordStatus;
import horse.sumomo.pos_doc_backend.persistence.repository.IngestionJobRepository;
import horse.sumomo.pos_doc_backend.persistence.repository.PosDocumentRepository;
import horse.sumomo.pos_doc_backend.persistence.repository.PosRecordRepository;
import horse.sumomo.pos_doc_backend.persistence.repository.StorageObjectRepository;
import horse.sumomo.pos_doc_backend.review.testsupport.ReviewTestFixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Persistence integration tests for {@link PosRecordCommandService} (PATCH,
 * verification, soft delete) over a temporary SQLite database with a fixed,
 * deterministic clock. Uses only synthetic metadata.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PosRecordCommandServiceIntegrationTest {

	private static final Instant FIXED_NOW = Instant.ofEpochMilli(1_700_001_000_000L);
	private static final OffsetDateTime FIXED_NOW_UTC = OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC);

	@Autowired
	private PosRecordCommandService commandService;

	@Autowired
	private PosRecordReadService readService;

	@Autowired
	private PosRecordSearchService searchService;

	@Autowired
	private PosDocumentListService documentListService;

	@Autowired
	private IngestionJobReadService ingestionJobReadService;

	@Autowired
	private PosRecordRepository posRecordRepository;

	@Autowired
	private StorageObjectRepository storageObjectRepository;

	@Autowired
	private PosDocumentRepository posDocumentRepository;

	@Autowired
	private IngestionJobRepository ingestionJobRepository;

	@MockitoBean
	private java.time.Clock clock;

	@BeforeEach
	void stubClock() {
		Mockito.when(this.clock.instant()).thenReturn(FIXED_NOW);
	}

	@DynamicPropertySource
	static void sqliteUrl(DynamicPropertyRegistry registry) throws Exception {
		Path dbFile = Files.createTempFile("pos-doc-review-cmd-test", ".db");
		dbFile.toFile().deleteOnExit();
		Path.of(dbFile.toString() + "-wal").toFile().deleteOnExit();
		Path.of(dbFile.toString() + "-shm").toFile().deleteOnExit();
		registry.add("SQLITE_URL", () -> "jdbc:sqlite:" + dbFile);
	}

	// ------------------------------------------------------------------
	// PATCH
	// ------------------------------------------------------------------

	@Test
	void patchChangesEveryEditableFieldAndKeepsNormalizedShadowConsistent() {
		PosRecordEntity record = reviewable("EREF-OLD", "POL-OLD", "Old Holder", "Old Consultant");

		PosRecord result = this.commandService.patch(record.getId(), new PosRecordPatch(version(record.getId()))
				.erefNumber("EREF-NEW-1")
				.policyNumber("POL-NEW-1")
				.policyholderName("New Holder")
				.consultantName("New Consultant")
				.policyCreateDate(LocalDate.of(2026, 3, 1)));

		assertEquals("EREF-NEW-1", result.getErefNumber());
		assertEquals("POL-NEW-1", result.getPolicyNumber());
		assertEquals("New Holder", result.getPolicyholderName());
		assertEquals("New Consultant", result.getConsultantName());
		assertEquals(LocalDate.of(2026, 3, 1), result.getPolicyCreateDate());

		PosRecordEntity reloaded = this.posRecordRepository.findById(record.getId()).orElseThrow();
		assertEquals("EREFNEW1", reloaded.getErefNumberNormalized());
		assertEquals("POLNEW1", reloaded.getPolicyNumberNormalized());
		assertEquals("new holder", reloaded.getPolicyholderNameNormalized());
		assertEquals("New Holder", reloaded.getPolicyholderName(), "display value is preserved");
		assertEquals("New Consultant", reloaded.getConsultantName());
	}

	@Test
	void patchLeavesOmittedFieldsUnchanged() {
		PosRecordEntity record = reviewable("EREF-KEEP", "POL-KEEP", "Keep Holder", "Keep Consultant");

		this.commandService.patch(record.getId(), new PosRecordPatch(version(record.getId()))
				.policyNumber("POL-CHANGED"));

		PosRecordEntity reloaded = this.posRecordRepository.findById(record.getId()).orElseThrow();
		assertEquals("EREF-KEEP", reloaded.getErefNumber());
		assertEquals("Keep Holder", reloaded.getPolicyholderName());
		assertEquals("Keep Consultant", reloaded.getConsultantName());
		assertEquals("POL-CHANGED", reloaded.getPolicyNumber());
	}

	@Test
	void noOpPatchChangesNothing() {
		PosRecordEntity record = reviewable("EREF-SAME", "POL-SAME", "Same Holder", "Same Consultant");
		long versionBefore = version(record.getId());
		Instant updatedAtBefore = record.getUpdatedAt();

		PosRecord result = this.commandService.patch(record.getId(), new PosRecordPatch(version(record.getId()))
				.erefNumber("  EREF-SAME  ")); // whitespace-only difference -> true no-op

		assertEquals(versionBefore, result.getVersion().longValue(), "no-op must not bump the version");
		PosRecordEntity reloaded = this.posRecordRepository.findById(record.getId()).orElseThrow();
		assertEquals(versionBefore, reloaded.getVersion());
		assertEquals(updatedAtBefore, reloaded.getUpdatedAt(), "no-op must not touch updatedAt");
	}

	@Test
	void editingACompletedRecordReturnsToReviewRequired() {
		PosRecordEntity record = recordWithStatus(PosRecordStatus.COMPLETED, "EREF-C", "POL-C", "Holder C", "Consultant C");

		PosRecord result = this.commandService.patch(record.getId(), new PosRecordPatch(version(record.getId()))
				.consultantName("Consultant C2"));

		assertEquals(com.yourcompany.pos.api.model.PosRecordStatus.REVIEW_REQUIRED, result.getStatus());
	}

	@Test
	void patchingANonReviewableStateReturns409() {
		PosRecordEntity record = recordWithStatus(PosRecordStatus.PROCESSING, "EREF-P", "POL-P", "Holder P",
				"Consultant P");

		PosRecordApiException ex = assertThrows(PosRecordApiException.class,
				() -> this.commandService.patch(record.getId(), new PosRecordPatch(version(record.getId()))
						.consultantName("X")));
		assertEquals(PosRecordApiException.Code.POS_RECORD_NOT_REVIEWABLE, ex.getCode());
	}

	@Test
	void duplicateNormalizedErefReturns409() {
		PosRecordEntity existing = reviewable("EREF-DUP-1", null, "Holder A", "Consultant A");
		PosRecordEntity target = recordWithStatus(PosRecordStatus.REVIEW_REQUIRED, "EREF-OTHER", null, "Holder B",
				"Consultant B");

		PosRecordApiException ex = assertThrows(PosRecordApiException.class,
				() -> this.commandService.patch(target.getId(), new PosRecordPatch(version(target.getId()))
						.erefNumber("eref dup 1")));
		assertEquals(PosRecordApiException.Code.DUPLICATE_EREF_NUMBER, ex.getCode());
	}

	@Test
	void duplicateNormalizedPolicyReturns409() {
		PosRecordEntity existing = reviewable(null, "POL-DUP-1", "Holder A", "Consultant A");
		PosRecordEntity target = recordWithStatus(PosRecordStatus.REVIEW_REQUIRED, "EREF-X", null, "Holder B",
				"Consultant B");

		PosRecordApiException ex = assertThrows(PosRecordApiException.class,
				() -> this.commandService.patch(target.getId(), new PosRecordPatch(version(target.getId()))
						.policyNumber("pol dup 1")));
		assertEquals(PosRecordApiException.Code.DUPLICATE_POLICY_NUMBER, ex.getCode());
	}

	@Test
	void staleVersionReturns412AndOverwritesNothing() {
		PosRecordEntity record = reviewable("EREF-STALE", "POL-STALE", "Holder S", "Consultant S");
		long version = version(record.getId());
		String consultantBefore = record.getConsultantName();

		PosRecordApiException ex = assertThrows(PosRecordApiException.class,
				() -> this.commandService.patch(record.getId(), new PosRecordPatch(version + 1)
						.consultantName("Should Not Apply")));
		assertEquals(PosRecordApiException.Code.POS_RECORD_VERSION_MISMATCH, ex.getCode());

		PosRecordEntity reloaded = this.posRecordRepository.findById(record.getId()).orElseThrow();
		assertEquals(version, reloaded.getVersion());
		assertEquals(consultantBefore, reloaded.getConsultantName());
	}

	@Test
	void patchSetsUpdatedAtFromTheFixedClockAndReturnsIncrementedVersion() {
		PosRecordEntity record = reviewable("EREF-TS", "POL-TS", "Holder T", "Consultant T");
		long version = version(record.getId());

		PosRecord result = this.commandService.patch(record.getId(), new PosRecordPatch(version)
				.consultantName("Consultant T2"));

		assertEquals(version + 1, result.getVersion().longValue());
		assertEquals(FIXED_NOW_UTC, result.getUpdatedAt());
	}

	@Test
	void patchOnUnknownRecordReturns404() {
		PosRecordApiException ex = assertThrows(PosRecordApiException.class,
				() -> this.commandService.patch(UUID.randomUUID(), new PosRecordPatch(0L)
						.consultantName("X")));
		assertEquals(PosRecordApiException.Code.POS_RECORD_NOT_FOUND, ex.getCode());
	}

	// ------------------------------------------------------------------
	// verification
	// ------------------------------------------------------------------

	@Test
	void verifyingACompleteRecordMovesItToCompleted() {
		PosRecordEntity record = ReviewTestFixtures.saveVerifiableRecord(this.posRecordRepository,
				this.posDocumentRepository, this.storageObjectRepository, this.ingestionJobRepository,
				"EREF-V", "POL-V", "Holder V", "Consultant V");
		long version = version(record.getId());
		long documentsBefore = this.posDocumentRepository.findByPosRecordIdOrderBySequenceNumberAsc(record.getId())
				.size();

		PosRecord result = this.commandService.verify(record.getId(),
				new com.yourcompany.pos.api.model.VerifyPosRecordRequest(version));

		assertEquals(com.yourcompany.pos.api.model.PosRecordStatus.COMPLETED, result.getStatus());
		assertEquals(version + 1, result.getVersion().longValue());
		// Verification must not fabricate documents or OCR work.
		assertEquals(documentsBefore,
				this.posDocumentRepository.findByPosRecordIdOrderBySequenceNumberAsc(record.getId()).size());
	}

	@Test
	void verifyWithMissingRequiredMetadataReturns422AndChangesNothing() {
		// All four required values except consultant must be present for success.
		PosRecordEntity record = ReviewTestFixtures.saveVerifiableRecord(this.posRecordRepository,
				this.posDocumentRepository, this.storageObjectRepository, this.ingestionJobRepository,
				"EREF-M", "POL-M", "Holder M", null);
		long version = version(record.getId());

		PosRecordApiException ex = assertThrows(PosRecordApiException.class,
				() -> this.commandService.verify(record.getId(),
						new com.yourcompany.pos.api.model.VerifyPosRecordRequest(version)));
		assertEquals(PosRecordApiException.Code.POS_RECORD_INCOMPLETE, ex.getCode());
		assertEquals(PosRecordStatus.REVIEW_REQUIRED,
				this.posRecordRepository.findById(record.getId()).orElseThrow().getStatus());
	}

	@Test
	void verifyWithNoDocumentsReturns422() {
		PosRecordEntity record = reviewableWithJob("EREF-NODOC", "POL-NODOC", "Holder N", "Consultant N");
		long version = version(record.getId());

		PosRecordApiException ex = assertThrows(PosRecordApiException.class,
				() -> this.commandService.verify(record.getId(),
						new com.yourcompany.pos.api.model.VerifyPosRecordRequest(version)));
		assertEquals(PosRecordApiException.Code.POS_RECORD_INCOMPLETE, ex.getCode());
	}

	@Test
	void verifyWithAnIncompleteDocumentReturns422() {
		PosRecordEntity record = reviewable("EREF-INCDOC", "POL-INCDOC", "Holder I", "Consultant I");
		record.setErefNumber("EREF-INCDOC");
		this.posDocumentRepository
				.saveAndFlush(new PosDocumentEntity(UUID.randomUUID(), record,
						savePdf(record.getId() + "/inc"), 1,
						horse.sumomo.pos_doc_backend.persistence.model.DocumentType.OTHER,
						DocumentProcessingStatus.PROCESSING));
		// Replace the COMPLETED document/job set: add a job so the only incompleteness
		// is the document.
		ensureCompletedJob(record);
		long version = version(record.getId());

		PosRecordApiException ex = assertThrows(PosRecordApiException.class,
				() -> this.commandService.verify(record.getId(),
						new com.yourcompany.pos.api.model.VerifyPosRecordRequest(version)));
		assertEquals(PosRecordApiException.Code.POS_RECORD_INCOMPLETE, ex.getCode());
	}

	@Test
	void verifyWithAnIncompleteJobReturns422() {
		PosRecordEntity record = reviewable("EREF-INCJOB", "POL-INCJOB", "Holder J", "Consultant J");
		// The record already has one COMPLETED document and one COMPLETED job from
		// the fixture; add a second, still-RUNNING job so the job check fails.
		this.ingestionJobRepository.saveAndFlush(new IngestionJobEntity(UUID.randomUUID(), record,
				JobStatus.RUNNING, 1, ReviewTestFixtures.T0));
		long version = version(record.getId());

		PosRecordApiException ex = assertThrows(PosRecordApiException.class,
				() -> this.commandService.verify(record.getId(),
						new com.yourcompany.pos.api.model.VerifyPosRecordRequest(version)));
		assertEquals(PosRecordApiException.Code.POS_RECORD_INCOMPLETE, ex.getCode());
	}

	@Test
	void verifyOnACompletedRecordReturns409() {
		PosRecordEntity record = recordWithStatus(PosRecordStatus.COMPLETED, "EREF-VC", "POL-VC", "Holder VC",
				"Consultant VC");
		ensureVerifiableCompleteness(record);

		PosRecordApiException ex = assertThrows(PosRecordApiException.class,
				() -> this.commandService.verify(record.getId(),
						new com.yourcompany.pos.api.model.VerifyPosRecordRequest(version(record.getId()))));
		assertEquals(PosRecordApiException.Code.POS_RECORD_NOT_REVIEWABLE, ex.getCode());
	}

	@Test
	void verifyWithStaleVersionReturns412() {
		PosRecordEntity record = ReviewTestFixtures.saveVerifiableRecord(this.posRecordRepository,
				this.posDocumentRepository, this.storageObjectRepository, this.ingestionJobRepository,
				"EREF-VS", "POL-VS", "Holder VS", "Consultant VS");

		PosRecordApiException ex = assertThrows(PosRecordApiException.class,
				() -> this.commandService.verify(record.getId(),
						new com.yourcompany.pos.api.model.VerifyPosRecordRequest(version(record.getId()) + 5)));
		assertEquals(PosRecordApiException.Code.POS_RECORD_VERSION_MISMATCH, ex.getCode());
	}

	// ------------------------------------------------------------------
	// soft delete
	// ------------------------------------------------------------------

	@Test
	void deleteStampsDeletedAtButLeavesRelatedRowsIntact() {
		PosRecordEntity record = reviewable("EREF-DEL", "POL-DEL", "Holder D", "Consultant D");
		IngestionJobEntity job = ensureCompletedJob(record);

		this.commandService.delete(record.getId());

		PosRecordEntity reloaded = this.posRecordRepository.findById(record.getId()).orElseThrow();
		assertNotNull(reloaded.getDeletedAt(), "deletedAt must be stamped");
		// Related rows are still physically present.
		assertEquals(1, this.posDocumentRepository.findByPosRecordIdOrderBySequenceNumberAsc(record.getId()).size());
		assertEquals(1, this.ingestionJobRepository.findByPosRecordIdOrderByCreatedAtAsc(record.getId()).size());
		assertTrue(this.storageObjectRepository.count() >= 2, "storage metadata must survive the soft delete");
	}

	@Test
	void deletedRecordIsExcludedFromAllPublicReads() {
		PosRecordEntity record = reviewable("EREF-DELEX", "POL-DELEX", "Holder DX", "Consultant DX");
		IngestionJobEntity job = ensureCompletedJob(record);
		UUID jobId = job.getId();

		this.commandService.delete(record.getId());

		assertThrows(PosRecordApiException.class, () -> this.readService.getRecord(record.getId()));
		assertTrue(this.searchService.search(new com.yourcompany.pos.api.model.PosRecordSearchRequest()
				.erefNumber("EREF-DELEX")).getItems().stream()
						.noneMatch(s -> s.getId().equals(record.getId())));
		assertThrows(IntakeException.class, () -> this.documentListService.listDocuments(record.getId()));
		assertThrows(IntakeException.class, () -> this.ingestionJobReadService.getJob(jobId));
	}

	@Test
	void deletingAMissingOrAlreadyDeletedRecordReturns404() {
		assertThrows404(() -> this.commandService.delete(UUID.randomUUID()));

		PosRecordEntity record = reviewable("EREF-DEL2", "POL-DEL2", "Holder D2", "Consultant D2");
		this.commandService.delete(record.getId());
		assertThrows404(() -> this.commandService.delete(record.getId()));
	}

	@Test
	void eRefAndPolicyCanBeReusedByANewActiveRecordAfterSoftDelete() {
		PosRecordEntity old = reviewable("EREF-REUSE", "POL-REUSE", "Holder R", "Consultant R");
		this.commandService.delete(old.getId());

		// A brand-new active record may reuse the same normalized eRef/policy.
		PosRecordEntity fresh = reviewable("EREF-REUSE", "POL-REUSE", "Holder R2", "Consultant R2");
		assertEquals(fresh.getId(), this.posRecordRepository
				.findByErefNumberNormalizedAndDeletedAtIsNull("EREFREUSE").orElseThrow().getId());
	}

	// ------------------------------------------------------------------
	// normalization failures -> sanitized 400 (never 500)
	// ------------------------------------------------------------------

	@Test
	void punctuationOnlyErefAndPolicyReturnNoPatchFields() {
		PosRecordEntity record = reviewable("EREF-PUNCT", "POL-PUNCT", "Holder P", "Consultant P");
		long v = version(record.getId());

		PosRecordApiException erefEx = assertThrows(PosRecordApiException.class,
				() -> this.commandService.patch(record.getId(), new PosRecordPatch(v).erefNumber("---")));
		assertEquals(PosRecordApiException.Code.NO_PATCH_FIELDS, erefEx.getCode());

		PosRecordApiException policyEx = assertThrows(PosRecordApiException.class,
				() -> this.commandService.patch(record.getId(), new PosRecordPatch(v).policyNumber("...")));
		assertEquals(PosRecordApiException.Code.NO_PATCH_FIELDS, policyEx.getCode());

		// The record is untouched (no version bump).
		assertEquals(v, version(record.getId()));
	}

	// ------------------------------------------------------------------
	// display-value correction + no-op detection
	// ------------------------------------------------------------------

	@Test
	void displayOnlyCorrectionsAreStored() {
		PosRecordEntity record = reviewable("EREFDC1", "POLDC1", "JANE TAN", "Consultant X");
		long v = version(record.getId());

		this.commandService.patch(record.getId(), new PosRecordPatch(v)
				.erefNumber("EREF-DC-1")
				.policyNumber("POL-DC-1")
				.policyholderName("Jane Tan"));

		PosRecordEntity reloaded = this.posRecordRepository.findById(record.getId()).orElseThrow();
		assertEquals("EREF-DC-1", reloaded.getErefNumber());
		assertEquals("EREFDC1", reloaded.getErefNumberNormalized());
		assertEquals("POL-DC-1", reloaded.getPolicyNumber());
		assertEquals("POLDC1", reloaded.getPolicyNumberNormalized());
		assertEquals("Jane Tan", reloaded.getPolicyholderName());
		assertEquals("jane tan", reloaded.getPolicyholderNameNormalized());
		assertEquals(v + 1, reloaded.getVersion(), "display-only correction bumps the version");
	}

	@Test
	void displayOnlyEditOnCompletedRecordReturnsToReviewRequired() {
		PosRecordEntity record = recordWithStatus(PosRecordStatus.COMPLETED, "EREFDC2", "POLDC2", "JANE TAN",
				"Consultant X");
		long v = version(record.getId());

		PosRecord result = this.commandService.patch(record.getId(), new PosRecordPatch(v)
				.erefNumber("EREF-DC-2")); // display-only (same normalized form)

		assertEquals(com.yourcompany.pos.api.model.PosRecordStatus.REVIEW_REQUIRED, result.getStatus());
	}

	@Test
	void whitespaceOnlyChangesAreTrueNoOps() {
		PosRecordEntity record = reviewable("EREFDC3", "POLDC3", "JANE TAN", "Consultant Tan");
		long v = version(record.getId());
		Instant updatedAtBefore = this.posRecordRepository.findById(record.getId()).orElseThrow().getUpdatedAt();

		PosRecord result = this.commandService.patch(record.getId(), new PosRecordPatch(v)
				.erefNumber("  EREFDC3  ")
				.policyNumber(" POLDC3 ")
				.policyholderName("  JANE TAN  ")
				.consultantName("  Consultant Tan  "));

		// True no-op: version, updatedAt, and status are all unchanged.
		assertEquals(v, result.getVersion().longValue());
		PosRecordEntity reloaded = this.posRecordRepository.findById(record.getId()).orElseThrow();
		assertEquals(v, reloaded.getVersion());
		assertEquals(updatedAtBefore, reloaded.getUpdatedAt());
		assertEquals(PosRecordStatus.REVIEW_REQUIRED, reloaded.getStatus());
	}

	@Test
	void consultantWhitespaceOnlyIsNoOpButRealChangeIsStoredTrimmed() {
		PosRecordEntity record = reviewable("EREFDC4", "POLDC4", "Holder C", "Consultant Tan");
		long v = version(record.getId());

		PosRecord noop = this.commandService.patch(record.getId(),
				new PosRecordPatch(v).consultantName("  Consultant Tan  "));
		assertEquals(v, noop.getVersion().longValue(), "whitespace-only consultant change is a no-op");

		PosRecord edited = this.commandService.patch(record.getId(),
				new PosRecordPatch(v).consultantName("  New Consultant  "));
		assertEquals(v + 1, edited.getVersion().longValue());
		PosRecordEntity reloaded = this.posRecordRepository.findById(record.getId()).orElseThrow();
		assertEquals("New Consultant", reloaded.getConsultantName(), "consultant stored trimmed");
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	private long version(UUID id) {
		return this.posRecordRepository.findById(id).orElseThrow().getVersion();
	}

	private void assertThrows404(Runnable action) {
		PosRecordApiException ex = assertThrows(PosRecordApiException.class, action::run);
		assertEquals(PosRecordApiException.Code.POS_RECORD_NOT_FOUND, ex.getCode());
	}

	private PosRecordEntity reviewable(String eref, String policy, String holder, String consultant) {
		return ReviewTestFixtures.saveVerifiableRecord(this.posRecordRepository, this.posDocumentRepository,
				this.storageObjectRepository, this.ingestionJobRepository, eref, policy, holder, consultant);
	}

	private PosRecordEntity recordWithStatus(PosRecordStatus status, String eref, String policy, String holder,
			String consultant) {
		var archive = ReviewTestFixtures.saveArchive(this.storageObjectRepository, "a-" + UUID.randomUUID());
		PosRecordEntity record = ReviewTestFixtures.saveRecord(this.posRecordRepository, archive, status,
				ReviewTestFixtures.T0, ReviewTestFixtures.T0);
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
		return this.posRecordRepository.saveAndFlush(record);
	}

	/**
	 * A REVIEW_REQUIRED record with metadata and a COMPLETED job but no documents
	 * (for the "no documents" verification case).
	 */
	private PosRecordEntity reviewableWithJob(String eref, String policy, String holder, String consultant) {
		var archive = ReviewTestFixtures.saveArchive(this.storageObjectRepository, "a-" + UUID.randomUUID());
		PosRecordEntity record = ReviewTestFixtures.saveRecord(this.posRecordRepository, archive,
				PosRecordStatus.REVIEW_REQUIRED, ReviewTestFixtures.T0, ReviewTestFixtures.T0);
		record.setErefNumber(eref);
		record.setPolicyNumber(policy);
		record.setPolicyholderName(holder);
		record.setConsultantName(consultant);
		record = this.posRecordRepository.saveAndFlush(record);
		ReviewTestFixtures.saveJob(this.ingestionJobRepository, record, JobStatus.COMPLETED, 1,
				ReviewTestFixtures.T0);
		return record;
	}

	private void ensureVerifiableCompleteness(PosRecordEntity record) {
		if (this.posDocumentRepository.findByPosRecordIdOrderBySequenceNumberAsc(record.getId()).isEmpty()) {
			ReviewTestFixtures.saveDocument(this.posDocumentRepository, this.storageObjectRepository, record, 0,
					DocumentProcessingStatus.COMPLETED);
		}
		ensureCompletedJob(record);
	}

	private IngestionJobEntity ensureCompletedJob(PosRecordEntity record) {
		var jobs = this.ingestionJobRepository.findByPosRecordIdOrderByCreatedAtAsc(record.getId());
		if (jobs.stream().anyMatch(j -> j.getStatus() == JobStatus.COMPLETED)) {
			return jobs.stream().filter(j -> j.getStatus() == JobStatus.COMPLETED).findFirst().orElseThrow();
		}
		return ReviewTestFixtures.saveJob(this.ingestionJobRepository, record, JobStatus.COMPLETED, 1,
				ReviewTestFixtures.T0);
	}

	private horse.sumomo.pos_doc_backend.persistence.entity.StorageObjectEntity savePdf(String key) {
		return this.storageObjectRepository.saveAndFlush(
				new horse.sumomo.pos_doc_backend.persistence.entity.StorageObjectEntity(UUID.randomUUID(), key,
						"document.pdf", "application/pdf", 64L, ReviewTestFixtures.SHA, ReviewTestFixtures.T0));
	}

}
