package horse.sumomo.pos_doc_backend.persistence.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import horse.sumomo.pos_doc_backend.persistence.entity.IngestionJobEntity;

/**
 * Repository for ingestion jobs.
 */
public interface IngestionJobRepository extends JpaRepository<IngestionJobEntity, UUID> {

	Optional<IngestionJobEntity> findByIdAndPosRecordDeletedAtIsNull(UUID id);

	List<IngestionJobEntity> findByPosRecordIdOrderByCreatedAtAsc(UUID posRecordId);

}
