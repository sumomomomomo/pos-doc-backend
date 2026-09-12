package horse.sumomo.pos_doc_backend.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import com.yourcompany.pos.api.model.PosRecordPatch;
import com.yourcompany.pos.api.model.VerifyPosRecordRequest;

import horse.sumomo.pos_doc_backend.persistence.entity.IngestionJobEntity;
import horse.sumomo.pos_doc_backend.persistence.entity.PosDocumentEntity;
import horse.sumomo.pos_doc_backend.persistence.entity.PosRecordEntity;
import horse.sumomo.pos_doc_backend.persistence.model.DocumentProcessingStatus;
import horse.sumomo.pos_doc_backend.persistence.model.JobStatus;
import horse.sumomo.pos_doc_backend.persistence.model.PosRecordStatus;
import horse.sumomo.pos_doc_backend.persistence.repository.IngestionJobRepository;
import horse.sumomo.pos_doc_backend.persistence.repository.PosDocumentRepository;
import horse.sumomo.pos_doc_backend.persistence.repository.PosRecordRepository;

/**
 * Pure unit tests for the flush-time failure paths of
 * {@link PosRecordCommandService}. These simulate the two kinds of race the
 * in-transaction pre-check cannot see:
 *
 * <ul>
 * <li>a {@code @Version} conflict surfaced at flush time by the driver
 * ({@link OptimisticLockingFailureException}) → sanitized 412/409;</li>
 * <li>a unique-constraint violation surfaced at flush time as a generic
 * {@link DataAccessException} (Xerial does not map it to
 * {@code DataIntegrityViolationException}) → resolved to the exact 409 code in a
 * separate read transaction; anything else is rethrown for the global 500
 * handler.</li>
 * </ul>
 *
 * The real service runs each command through a {@code TransactionTemplate}, so
 * the {@link PlatformTransactionManager} is mocked to let the template's
 * callback execute and let flush-time exceptions propagate.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PosRecordCommandServiceConcurrencyTest {

	private static final UUID ID = UUID.randomUUID();
	private static final long CURRENT_VERSION = 5L;

	@Mock
	private PosRecordRepository posRecordRepository;

	@Mock
	private PosDocumentRepository posDocumentRepository;

	@Mock
	private IngestionJobRepository ingestionJobRepository;

	@Mock
	private PlatformTransactionManager transactionManager;

	@Mock
	private java.time.Clock clock;

	private TransactionStatus transactionStatus;

	private PosRecordCommandService service;

	@BeforeEach
	void setUp() {
		this.transactionStatus = mock(TransactionStatus.class);
		when(this.transactionManager.getTransaction(any())).thenReturn(this.transactionStatus);
		when(this.clock.instant()).thenReturn(Instant.ofEpochMilli(1_700_000_000_000L));
		this.service = new PosRecordCommandService(this.posRecordRepository, this.posDocumentRepository,
				this.ingestionJobRepository, this.clock, this.transactionManager);
	}

	@Test
	void patchFlushTimeVersionConflictMapsTo412() {
		PosRecordEntity entity = record(CURRENT_VERSION, "OLDEREF", null);
		// No in-transaction duplicate; the @Version conflict appears only at flush.
		when(this.posRecordRepository.findByIdAndDeletedAtIsNull(ID)).thenReturn(Optional.of(entity));
		when(this.posRecordRepository.existsByErefNumberNormalizedAndDeletedAtIsNull(any())).thenReturn(false);
		when(this.posRecordRepository.saveAndFlush(any()))
				.thenThrow(new OptimisticLockingFailureException("concurrent update"));

		PosRecordApiException ex = assertThrows(PosRecordApiException.class,
				() -> this.service.patch(ID, patch(CURRENT_VERSION, "new-eref", null)));
		assertEquals(PosRecordApiException.Code.POS_RECORD_VERSION_MISMATCH, ex.getCode());
	}

	@Test
	void patchFlushTimeErefRaceResolvesToDuplicateEref409() {
		PosRecordEntity entity = record(CURRENT_VERSION, "OLDEREF", null);
		when(this.posRecordRepository.findByIdAndDeletedAtIsNull(ID)).thenReturn(Optional.of(entity));
		// The pre-check (write tx) sees no duplicate, but by the time the read tx
		// re-checks after the flush failure a concurrent row has been committed.
		when(this.posRecordRepository.existsByErefNumberNormalizedAndDeletedAtIsNull(any()))
				.thenReturn(false, true);
		when(this.posRecordRepository.saveAndFlush(any()))
				.thenThrow(mock(DataAccessException.class));

		PosRecordApiException ex = assertThrows(PosRecordApiException.class,
				() -> this.service.patch(ID, patch(CURRENT_VERSION, "new-eref", null)));
		assertEquals(PosRecordApiException.Code.DUPLICATE_EREF_NUMBER, ex.getCode());
	}

	@Test
	void patchFlushTimePolicyRaceResolvesToDuplicatePolicy409() {
		PosRecordEntity entity = record(CURRENT_VERSION, null, "OLDPOLICY");
		when(this.posRecordRepository.findByIdAndDeletedAtIsNull(ID)).thenReturn(Optional.of(entity));
		when(this.posRecordRepository.existsByPolicyNumberNormalizedAndDeletedAtIsNull(any()))
				.thenReturn(false, true);
		when(this.posRecordRepository.saveAndFlush(any()))
				.thenThrow(mock(DataAccessException.class));

		PosRecordApiException ex = assertThrows(PosRecordApiException.class,
				() -> this.service.patch(ID, patch(CURRENT_VERSION, null, "new-policy")));
		assertEquals(PosRecordApiException.Code.DUPLICATE_POLICY_NUMBER, ex.getCode());
	}

	@Test
	void patchFlushTimeNonDuplicateConflictIsRethrownForThe500Handler() {
		PosRecordEntity entity = record(CURRENT_VERSION, "OLDEREF", null);
		when(this.posRecordRepository.findByIdAndDeletedAtIsNull(ID)).thenReturn(Optional.of(entity));
		// Neither field is actually duplicated after the flush failure.
		when(this.posRecordRepository.existsByErefNumberNormalizedAndDeletedAtIsNull(any()))
				.thenReturn(false, false);
		DataAccessException dbError = mock(DataAccessException.class);
		when(this.posRecordRepository.saveAndFlush(any())).thenThrow(dbError);

		assertThrows(DataAccessException.class,
				() -> this.service.patch(ID, patch(CURRENT_VERSION, "new-eref", null)));
	}

	@Test
	void verifyFlushTimeVersionConflictMapsTo412() {
		PosRecordEntity entity = completeRecord(CURRENT_VERSION);
		when(this.posRecordRepository.findByIdAndDeletedAtIsNull(ID)).thenReturn(Optional.of(entity));
		when(this.posRecordRepository.saveAndFlush(any()))
				.thenThrow(new OptimisticLockingFailureException("concurrent update"));

		PosRecordApiException ex = assertThrows(PosRecordApiException.class,
				() -> this.service.verify(ID, new VerifyPosRecordRequest(CURRENT_VERSION)));
		assertEquals(PosRecordApiException.Code.POS_RECORD_VERSION_MISMATCH, ex.getCode());
	}

	@Test
	void deleteFlushTimeVersionConflictMapsToDeleteConflict409() {
		PosRecordEntity entity = record(CURRENT_VERSION, null, null);
		when(this.posRecordRepository.findByIdAndDeletedAtIsNull(ID)).thenReturn(Optional.of(entity));
		when(this.posRecordRepository.saveAndFlush(any()))
				.thenThrow(new OptimisticLockingFailureException("concurrent update"));

		PosRecordApiException ex = assertThrows(PosRecordApiException.class,
				() -> this.service.delete(ID));
		assertEquals(PosRecordApiException.Code.POS_RECORD_DELETE_CONFLICT, ex.getCode());
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	private PosRecordEntity record(long version, String erefNormalized, String policyNormalized) {
		PosRecordEntity entity = mock(PosRecordEntity.class);
		when(entity.getVersion()).thenReturn(version);
		when(entity.getStatus()).thenReturn(PosRecordStatus.REVIEW_REQUIRED);
		when(entity.getErefNumberNormalized()).thenReturn(erefNormalized);
		when(entity.getPolicyNumberNormalized()).thenReturn(policyNormalized);
		return entity;
	}

	private PosRecordEntity completeRecord(long version) {
		PosRecordEntity entity = record(version, "E", "P");
		when(entity.getPolicyholderNameNormalized()).thenReturn("H");
		when(entity.getConsultantName()).thenReturn("Consultant");
		PosDocumentEntity document = mock(PosDocumentEntity.class);
		when(document.getProcessingStatus()).thenReturn(DocumentProcessingStatus.COMPLETED);
		IngestionJobEntity job = mock(IngestionJobEntity.class);
		when(job.getStatus()).thenReturn(JobStatus.COMPLETED);
		when(this.posDocumentRepository.findByPosRecordIdOrderBySequenceNumberAsc(ID)).thenReturn(List.of(document));
		when(this.ingestionJobRepository.findByPosRecordIdOrderByCreatedAtAsc(ID)).thenReturn(List.of(job));
		return entity;
	}

	private PosRecordPatch patch(long expectedVersion, String eref, String policy) {
		PosRecordPatch result = new PosRecordPatch(expectedVersion);
		if (eref != null) {
			result = result.erefNumber(eref);
		}
		if (policy != null) {
			result = result.policyNumber(policy);
		}
		return result;
	}

}
