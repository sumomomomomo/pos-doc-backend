package horse.sumomo.pos_doc_backend.ingestion.application;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import horse.sumomo.pos_doc_backend.ingestion.application.IntakeException;
import horse.sumomo.pos_doc_backend.persistence.entity.PosDocumentEntity;
import horse.sumomo.pos_doc_backend.persistence.repository.PosDocumentRepository;
import horse.sumomo.pos_doc_backend.persistence.repository.PosRecordRepository;

/**
 * Read service for the {@code GET /api/v1/pos-records/{id}/documents}
 * endpoint.
 *
 * <p>Returns the persisted documents ordered by {@code sequence_number ASC}.
 * Returns {@code []} before extraction. Returns {@code 404} when the POS
 * record is missing or soft-deleted. No MinIO calls; no presigned URLs.
 */
@Service
public class PosDocumentListService {

	private final PosRecordRepository posRecordRepository;
	private final PosDocumentRepository posDocumentRepository;

	public PosDocumentListService(PosRecordRepository posRecordRepository,
			PosDocumentRepository posDocumentRepository) {
		this.posRecordRepository = Objects.requireNonNull(posRecordRepository);
		this.posDocumentRepository = Objects.requireNonNull(posDocumentRepository);
	}

	/**
	 * Reads the document list inside a read-only transaction so the
	 * Open-Session-in-View anti-pattern stays disabled.
	 */
	@Transactional(readOnly = true)
	public List<PosDocumentEntity> listDocuments(UUID posRecordId) {
		if (!this.posRecordRepository.findByIdAndDeletedAtIsNull(posRecordId).isPresent()) {
			throw new IntakeException(IntakeException.Code.INGESTION_JOB_NOT_FOUND);
		}
		return this.posDocumentRepository.findWithAssociationsByPosRecordId(posRecordId);
	}

}