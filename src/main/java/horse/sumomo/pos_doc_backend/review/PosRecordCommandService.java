package horse.sumomo.pos_doc_backend.review;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.yourcompany.pos.api.model.PosRecord;
import com.yourcompany.pos.api.model.PosRecordPatch;
import com.yourcompany.pos.api.model.VerifyPosRecordRequest;

import horse.sumomo.pos_doc_backend.persistence.entity.IngestionJobEntity;
import horse.sumomo.pos_doc_backend.persistence.entity.PosDocumentEntity;
import horse.sumomo.pos_doc_backend.persistence.entity.PosRecordEntity;
import horse.sumomo.pos_doc_backend.persistence.model.DocumentProcessingStatus;
import horse.sumomo.pos_doc_backend.persistence.model.JobStatus;
import horse.sumomo.pos_doc_backend.persistence.model.PosRecordStatus;
import horse.sumomo.pos_doc_backend.persistence.normalization.MetadataNormalizer;
import horse.sumomo.pos_doc_backend.persistence.repository.IngestionJobRepository;
import horse.sumomo.pos_doc_backend.persistence.repository.PosDocumentRepository;
import horse.sumomo.pos_doc_backend.persistence.repository.PosRecordRepository;

/**
 * Command service for the POS-record review API: PATCH, verification, and soft
 * delete.
 *
 * <p>Each command runs in one short, explicitly-managed transaction
 * ({@link TransactionTemplate}). Because the pool is a single connection and
 * Open-Session-in-View is disabled, transactions are strictly sequential (never
 * nested) and the {@code sourceArchive} association is mapped to the DTO inside
 * the transaction. A unique-constraint race that the in-transaction pre-check
 * misses is resolved in a separate read-only transaction; the driver's raw
 * message is never exposed. The optimistic-lock {@code @Version} field is the
 * only revision mechanism.
 */
@Service
public class PosRecordCommandService {

	private final PosRecordRepository posRecordRepository;
	private final PosDocumentRepository posDocumentRepository;
	private final IngestionJobRepository ingestionJobRepository;
	private final Clock clock;
	private final TransactionTemplate writeTx;
	private final TransactionTemplate readTx;

	public PosRecordCommandService(PosRecordRepository posRecordRepository,
			PosDocumentRepository posDocumentRepository,
			IngestionJobRepository ingestionJobRepository,
			Clock clock,
			PlatformTransactionManager transactionManager) {
		this.posRecordRepository = Objects.requireNonNull(posRecordRepository);
		this.posDocumentRepository = Objects.requireNonNull(posDocumentRepository);
		this.ingestionJobRepository = Objects.requireNonNull(ingestionJobRepository);
		this.clock = Objects.requireNonNull(clock);
		PlatformTransactionManager manager = Objects.requireNonNull(transactionManager);
		this.writeTx = new TransactionTemplate(manager);
		this.readTx = new TransactionTemplate(manager);
		this.readTx.setReadOnly(true);
	}

	/**
	 * Applies a metadata correction. See the OpenAPI PATCH description for the
	 * full contract (reviewable state, no-op, uniqueness, and
	 * COMPLETED-returns-to-REVIEW_REQUIRED behavior).
	 */
	public PosRecord patch(UUID posRecordId, PosRecordPatch patch) {
		Objects.requireNonNull(posRecordId, "posRecordId must not be null");
		Objects.requireNonNull(patch, "patch must not be null");

		String erefRaw = patch.getErefNumber();
		String policyRaw = patch.getPolicyNumber();
		String holderRaw = patch.getPolicyholderName();
		String consultantRaw = patch.getConsultantName();
		LocalDate createDate = patch.getPolicyCreateDate();

		if (isBlank(erefRaw) || isBlank(policyRaw) || isBlank(holderRaw) || isBlank(consultantRaw)) {
			throw new PosRecordApiException(PosRecordApiException.Code.NO_PATCH_FIELDS);
		}
		if (erefRaw == null && policyRaw == null && holderRaw == null && consultantRaw == null && createDate == null) {
			throw new PosRecordApiException(PosRecordApiException.Code.NO_PATCH_FIELDS);
		}
		long expectedVersion = requireVersion(patch.getExpectedVersion());

		final String erefNorm = erefRaw == null ? null : MetadataNormalizer.normalizeIdentifier(erefRaw);
		final String policyNorm = policyRaw == null ? null : MetadataNormalizer.normalizeIdentifier(policyRaw);
		final String holderNorm = holderRaw == null ? null : MetadataNormalizer.normalizeName(holderRaw);

		// Set inside the write transaction (they depend on the persisted values) and
		// read afterwards to resolve a flush-time unique-constraint race.
		final boolean[] changed = new boolean[2]; // [0]=eref, [1]=policy

		PosRecord result;
		try {
			result = this.writeTx.execute(status -> {
				PosRecordEntity entity = this.posRecordRepository.findByIdAndDeletedAtIsNull(posRecordId)
						.orElseThrow(() -> new PosRecordApiException(PosRecordApiException.Code.POS_RECORD_NOT_FOUND));
				if (entity.getVersion() != expectedVersion) {
					throw new PosRecordApiException(PosRecordApiException.Code.POS_RECORD_VERSION_MISMATCH);
				}
				PosRecordStatus state = entity.getStatus();
				if (state != PosRecordStatus.REVIEW_REQUIRED && state != PosRecordStatus.COMPLETED) {
					throw new PosRecordApiException(PosRecordApiException.Code.POS_RECORD_NOT_REVIEWABLE);
				}
				boolean wasCompleted = state == PosRecordStatus.COMPLETED;

				boolean eChanged = erefRaw != null && !Objects.equals(erefNorm, entity.getErefNumberNormalized());
				boolean pChanged = policyRaw != null && !Objects.equals(policyNorm, entity.getPolicyNumberNormalized());
				boolean hChanged = holderRaw != null && !Objects.equals(holderNorm, entity.getPolicyholderNameNormalized());
				boolean cChanged = consultantRaw != null && !Objects.equals(consultantRaw, entity.getConsultantName());
				boolean dChanged = createDate != null && !Objects.equals(createDate, entity.getPolicyCreateDate());
				boolean realChange = eChanged || pChanged || hChanged || cChanged || dChanged;

				if (!realChange) {
					return PosRecordApiMapper.toRecord(entity);
				}

				if (eChanged && this.posRecordRepository
						.existsByErefNumberNormalizedAndDeletedAtIsNull(erefNorm)) {
					throw new PosRecordApiException(PosRecordApiException.Code.DUPLICATE_EREF_NUMBER);
				}
				if (pChanged && this.posRecordRepository
						.existsByPolicyNumberNormalizedAndDeletedAtIsNull(policyNorm)) {
					throw new PosRecordApiException(PosRecordApiException.Code.DUPLICATE_POLICY_NUMBER);
				}

				changed[0] = eChanged;
				changed[1] = pChanged;
				if (eChanged) {
					entity.setErefNumber(erefRaw);
				}
				if (pChanged) {
					entity.setPolicyNumber(policyRaw);
				}
				if (hChanged) {
					entity.setPolicyholderName(holderRaw);
				}
				if (cChanged) {
					entity.setConsultantName(consultantRaw);
				}
				if (dChanged) {
					entity.setPolicyCreateDate(createDate);
				}
				entity.setUpdatedAt(this.clock.instant());
				if (wasCompleted) {
					entity.setStatus(PosRecordStatus.REVIEW_REQUIRED);
				}
				PosRecordEntity saved = this.posRecordRepository.saveAndFlush(entity);
				return PosRecordApiMapper.toRecord(saved);
			});
		}
		catch (OptimisticLockingFailureException ex) {
			throw new PosRecordApiException(PosRecordApiException.Code.POS_RECORD_VERSION_MISMATCH);
		}
		catch (DataAccessException ex) {
			throw resolveConstraintConflict(changed[0], erefNorm, changed[1], policyNorm, ex);
		}
		return result;
	}

	/**
	 * The only path from {@code REVIEW_REQUIRED} to {@code COMPLETED}. Performs no
	 * MinIO, RabbitMQ, rendering, or OCR work.
	 */
	public PosRecord verify(UUID posRecordId, VerifyPosRecordRequest request) {
		Objects.requireNonNull(posRecordId, "posRecordId must not be null");
		Objects.requireNonNull(request, "request must not be null");
		long expectedVersion = requireVersion(request.getExpectedVersion());

		try {
			return this.writeTx.execute(status -> {
				PosRecordEntity entity = this.posRecordRepository.findByIdAndDeletedAtIsNull(posRecordId)
						.orElseThrow(() -> new PosRecordApiException(PosRecordApiException.Code.POS_RECORD_NOT_FOUND));
				if (entity.getVersion() != expectedVersion) {
					throw new PosRecordApiException(PosRecordApiException.Code.POS_RECORD_VERSION_MISMATCH);
				}
				if (entity.getStatus() != PosRecordStatus.REVIEW_REQUIRED) {
					throw new PosRecordApiException(PosRecordApiException.Code.POS_RECORD_NOT_REVIEWABLE);
				}
				if (!this.meetsVerificationPrerequisites(entity, posRecordId)) {
					throw new PosRecordApiException(PosRecordApiException.Code.POS_RECORD_INCOMPLETE);
				}
				entity.setStatus(PosRecordStatus.COMPLETED);
				entity.setUpdatedAt(this.clock.instant());
				PosRecordEntity saved = this.posRecordRepository.saveAndFlush(entity);
				return PosRecordApiMapper.toRecord(saved);
			});
		}
		catch (OptimisticLockingFailureException ex) {
			throw new PosRecordApiException(PosRecordApiException.Code.POS_RECORD_VERSION_MISMATCH);
		}
	}

	/**
	 * Soft-deletes the active record. Related storage, documents, jobs, and OCR
	 * rows are left intact. A concurrent optimistic-lock failure maps to a
	 * sanitized 409.
	 */
	public void delete(UUID posRecordId) {
		Objects.requireNonNull(posRecordId, "posRecordId must not be null");
		try {
			this.writeTx.executeWithoutResult(status -> {
				PosRecordEntity entity = this.posRecordRepository.findByIdAndDeletedAtIsNull(posRecordId)
						.orElseThrow(() -> new PosRecordApiException(PosRecordApiException.Code.POS_RECORD_NOT_FOUND));
				entity.markDeleted(this.clock.instant());
				this.posRecordRepository.saveAndFlush(entity);
			});
		}
		catch (OptimisticLockingFailureException ex) {
			throw new PosRecordApiException(PosRecordApiException.Code.POS_RECORD_DELETE_CONFLICT);
		}
	}

	private boolean meetsVerificationPrerequisites(PosRecordEntity entity, UUID posRecordId) {
		List<PosDocumentEntity> documents = this.posDocumentRepository
				.findByPosRecordIdOrderBySequenceNumberAsc(posRecordId);
		if (documents.isEmpty()) {
			return false;
		}
		for (PosDocumentEntity document : documents) {
			if (document.getProcessingStatus() != DocumentProcessingStatus.COMPLETED) {
				return false;
			}
		}
		List<IngestionJobEntity> jobs = this.ingestionJobRepository
				.findByPosRecordIdOrderByCreatedAtAsc(posRecordId);
		if (jobs.isEmpty()) {
			return false;
		}
		for (IngestionJobEntity job : jobs) {
			if (job.getStatus() != JobStatus.COMPLETED) {
				return false;
			}
		}
		return entity.getErefNumberNormalized() != null
				&& entity.getPolicyNumberNormalized() != null
				&& entity.getPolicyholderNameNormalized() != null
				&& entity.getConsultantName() != null
				&& !entity.getConsultantName().isBlank();
	}

	private PosRecordApiException resolveConstraintConflict(boolean erefChanged, String erefNorm,
			boolean policyChanged, String policyNorm, DataAccessException cause) {
		DuplicateResult found = this.readTx.execute(status -> {
			boolean eref = erefChanged
					&& this.posRecordRepository.existsByErefNumberNormalizedAndDeletedAtIsNull(erefNorm);
			boolean policy = policyChanged
					&& this.posRecordRepository.existsByPolicyNumberNormalizedAndDeletedAtIsNull(policyNorm);
			return new DuplicateResult(eref, policy);
		});
		if (found.eref()) {
			return new PosRecordApiException(PosRecordApiException.Code.DUPLICATE_EREF_NUMBER);
		}
		if (found.policy()) {
			return new PosRecordApiException(PosRecordApiException.Code.DUPLICATE_POLICY_NUMBER);
		}
		// Not a known duplicate: rethrow the original failure; the global handler
		// sanitizes it to a generic 500 (no driver text is ever exposed).
		throw cause;
	}

	private static long requireVersion(Long value) {
		if (value == null) {
			throw new PosRecordApiException(PosRecordApiException.Code.NO_PATCH_FIELDS);
		}
		return value;
	}

	private static boolean isBlank(String value) {
		return value != null && value.isBlank();
	}

	private record DuplicateResult(boolean eref, boolean policy) {
	}

}
