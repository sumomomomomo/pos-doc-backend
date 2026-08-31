package horse.sumomo.pos_doc_backend.ingestion.mapping;

import com.yourcompany.pos.api.model.DocumentProcessingStatus;
import com.yourcompany.pos.api.model.DocumentType;
import com.yourcompany.pos.api.model.PosDocument;
import com.yourcompany.pos.api.model.StorageObjectSummary;

import horse.sumomo.pos_doc_backend.persistence.entity.PosDocumentEntity;

/**
 * Maps a persisted {@link PosDocumentEntity} to the generated
 * {@link PosDocument} DTO.
 *
 * <p>Only scalar fields are read: the lazy {@code posRecord} association is
 * dereferenced only for its identifier (a primary-key projection, not a
 * collection), and the lazy {@code storageObject} association is dereferenced
 * only for its scalar metadata.
 */
public final class PosDocumentApiMapper {

	private PosDocumentApiMapper() {
	}

	public static PosDocument toDto(PosDocumentEntity entity) {
		StorageObjectSummary storage = new StorageObjectSummary(entity.getStorageObject().getId(),
				entity.getStorageObject().getOriginalFilename(), entity.getStorageObject().getContentType(),
				entity.getStorageObject().getByteSize(), entity.getStorageObject().getSha256());
		return new PosDocument(entity.getId(), entity.getPosRecord().getId(), storage,
				DocumentType.valueOf(entity.getDocumentType().name()),
				DocumentProcessingStatus.valueOf(entity.getProcessingStatus().name()));
	}

}