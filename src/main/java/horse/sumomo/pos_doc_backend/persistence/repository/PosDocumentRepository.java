package horse.sumomo.pos_doc_backend.persistence.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import horse.sumomo.pos_doc_backend.persistence.entity.PosDocumentEntity;

/**
 * Repository for POS documents.
 */
public interface PosDocumentRepository extends JpaRepository<PosDocumentEntity, UUID> {

	List<PosDocumentEntity> findByPosRecordIdOrderBySequenceNumberAsc(UUID posRecordId);

	/**
	 * Fetches all documents for a POS record with their {@code posRecord}
	 * and {@code storageObject} associations eagerly joined so the
	 * controller can map them to DTOs outside the transaction boundary.
	 */
	@Query("""
			SELECT d FROM PosDocumentEntity d
			JOIN FETCH d.posRecord
			JOIN FETCH d.storageObject
			WHERE d.posRecord.id = :posRecordId
			ORDER BY d.sequenceNumber ASC
			""")
	List<PosDocumentEntity> findWithAssociationsByPosRecordId(@Param("posRecordId") UUID posRecordId);

}
