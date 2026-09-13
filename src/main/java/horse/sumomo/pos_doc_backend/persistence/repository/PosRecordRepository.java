package horse.sumomo.pos_doc_backend.persistence.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import horse.sumomo.pos_doc_backend.persistence.entity.PosRecordEntity;

/**
 * Repository for POS records.
 *
 * <p>Application code must use the active-record variants (filtering on
 * {@code deletedAt IS NULL}) for normal reads. The inherited
 * {@code findById} remains available for internal persistence tests and
 * future administrative use.
 *
 * <p>{@link JpaSpecificationExecutor} supports the dynamic active-record search
 * filter; the search service always adds a {@code deletedAt IS NULL} predicate.
 */
public interface PosRecordRepository extends JpaRepository<PosRecordEntity, UUID>,
		JpaSpecificationExecutor<PosRecordEntity> {

	Optional<PosRecordEntity> findByIdAndDeletedAtIsNull(UUID id);

	@Query("SELECT r FROM PosRecordEntity r JOIN FETCH r.sourceArchive "
			+ "WHERE r.id = :posRecordId AND r.deletedAt IS NULL")
	Optional<PosRecordEntity> findActiveRecordWithSourceArchive(@Param("posRecordId") UUID posRecordId);

	Optional<PosRecordEntity> findByErefNumberNormalizedAndDeletedAtIsNull(String normalized);

	Optional<PosRecordEntity> findByPolicyNumberNormalizedAndDeletedAtIsNull(String normalized);

	boolean existsByErefNumberNormalizedAndDeletedAtIsNull(String normalized);

	boolean existsByPolicyNumberNormalizedAndDeletedAtIsNull(String normalized);

}
