package horse.sumomo.pos_doc_backend.persistence.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import horse.sumomo.pos_doc_backend.persistence.entity.StorageObjectEntity;

/**
 * Repository for MinIO object metadata.
 */
public interface StorageObjectRepository extends JpaRepository<StorageObjectEntity, UUID> {

	Optional<StorageObjectEntity> findByObjectKey(String objectKey);

	boolean existsByObjectKey(String objectKey);

}
