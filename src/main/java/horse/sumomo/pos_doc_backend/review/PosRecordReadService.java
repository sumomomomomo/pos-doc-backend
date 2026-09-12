package horse.sumomo.pos_doc_backend.review;

import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yourcompany.pos.api.model.PosRecord;

import horse.sumomo.pos_doc_backend.persistence.entity.PosRecordEntity;
import horse.sumomo.pos_doc_backend.persistence.repository.PosRecordRepository;

/**
 * Read service for {@code GET /api/v1/pos-records/{id}}.
 *
 * <p>Loads the active (not soft-deleted) record and maps it to the DTO inside a
 * read-only transaction, so the lazy {@code sourceArchive} association is
 * resolved while a session is open (Open-Session-in-View stays disabled). A
 * missing or soft-deleted record is {@code 404 POS_RECORD_NOT_FOUND}.
 */
@Service
public class PosRecordReadService {

	private final PosRecordRepository posRecordRepository;

	public PosRecordReadService(PosRecordRepository posRecordRepository) {
		this.posRecordRepository = Objects.requireNonNull(posRecordRepository);
	}

	@Transactional(readOnly = true)
	public PosRecord getRecord(UUID posRecordId) {
		PosRecordEntity entity = this.posRecordRepository.findByIdAndDeletedAtIsNull(posRecordId)
				.orElseThrow(() -> new PosRecordApiException(PosRecordApiException.Code.POS_RECORD_NOT_FOUND));
		return PosRecordApiMapper.toRecord(entity);
	}

}
