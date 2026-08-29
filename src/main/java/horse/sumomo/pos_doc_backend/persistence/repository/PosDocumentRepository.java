package horse.sumomo.pos_doc_backend.persistence.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import horse.sumomo.pos_doc_backend.persistence.entity.PosDocumentEntity;

/**
 * Repository for POS documents.
 */
public interface PosDocumentRepository extends JpaRepository<PosDocumentEntity, UUID> {

	List<PosDocumentEntity> findByPosRecordIdOrderBySequenceNumberAsc(UUID posRecordId);

}
