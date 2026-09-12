package horse.sumomo.pos_doc_backend.review;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import com.yourcompany.pos.api.model.PosRecord;
import com.yourcompany.pos.api.model.PosRecordStatus;
import com.yourcompany.pos.api.model.PosRecordSummary;
import com.yourcompany.pos.api.model.StorageObjectSummary;

import horse.sumomo.pos_doc_backend.persistence.entity.PosRecordEntity;
import horse.sumomo.pos_doc_backend.persistence.entity.StorageObjectEntity;

/**
 * Maps persisted entities to the generated API DTOs.
 *
 * <p>Only the public, user-facing fields are exposed: display metadata (preserving
 * the user's spelling/casing), status, timestamps, uploader, version, and the
 * source-archive storage summary. Normalized shadow columns, the deletion
 * timestamp, object keys, and any OCR text are never mapped.
 *
 * <p>{@link #toRecord(PosRecordEntity)} dereferences the lazy {@code sourceArchive}
 * association, so it must be invoked while a persistence session is open
 * (inside the caller's transaction) with Open-Session-in-View disabled.
 */
public final class PosRecordApiMapper {

	private PosRecordApiMapper() {
	}

	public static PosRecord toRecord(PosRecordEntity entity) {
		return new PosRecord(entity.getId(), toStatus(entity), toSummary(entity.getSourceArchive()),
				toOffsetDateTime(entity.getUploadedAt()), toOffsetDateTime(entity.getUpdatedAt()),
				entity.getUploadedBy(), entity.getVersion())
				.erefNumber(entity.getErefNumber())
				.policyNumber(entity.getPolicyNumber())
				.policyholderName(entity.getPolicyholderName())
				.consultantName(entity.getConsultantName())
				.policyCreateDate(entity.getPolicyCreateDate());
	}

	public static PosRecordSummary toSummary(PosRecordEntity entity) {
		return new PosRecordSummary(entity.getId(), toStatus(entity),
				toOffsetDateTime(entity.getUploadedAt()), toOffsetDateTime(entity.getUpdatedAt()))
				.erefNumber(entity.getErefNumber())
				.policyNumber(entity.getPolicyNumber())
				.policyholderName(entity.getPolicyholderName())
				.consultantName(entity.getConsultantName())
				.policyCreateDate(entity.getPolicyCreateDate());
	}

	public static StorageObjectSummary toSummary(StorageObjectEntity storage) {
		return new StorageObjectSummary(storage.getId(), storage.getOriginalFilename(),
				storage.getContentType(), storage.getByteSize(), storage.getSha256());
	}

	private static PosRecordStatus toStatus(PosRecordEntity entity) {
		return PosRecordStatus.valueOf(entity.getStatus().name());
	}

	private static OffsetDateTime toOffsetDateTime(Instant instant) {
		return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
	}

}
