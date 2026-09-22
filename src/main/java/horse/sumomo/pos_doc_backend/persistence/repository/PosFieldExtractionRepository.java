package horse.sumomo.pos_doc_backend.persistence.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import horse.sumomo.pos_doc_backend.persistence.entity.PosFieldExtractionEntity;
import horse.sumomo.pos_doc_backend.persistence.entity.PosFieldExtractionId;

/**
 * Repository for durable structured field-extraction outcomes.
 *
 * <p>All queries use explicit JPQL because the composite key fields
 * ({@code documentId}, {@code fieldName}, {@code promptVersion}) are nested
 * inside the {@link PosFieldExtractionId} embedded ID. The count query joins
 * through {@code PosDocumentEntity} to reach the POS record but selects only a
 * scalar count, so it never loads value text merely to count rows.
 */
public interface PosFieldExtractionRepository extends JpaRepository<PosFieldExtractionEntity, PosFieldExtractionId> {

	@Query("""
			SELECT e FROM PosFieldExtractionEntity e
			WHERE e.id.documentId = :documentId
			  AND e.id.fieldName = :fieldName
			  AND e.id.promptVersion = :promptVersion
			""")
	Optional<PosFieldExtractionEntity> findByDocumentIdAndFieldNameAndPromptVersion(
			@Param("documentId") UUID documentId, @Param("fieldName") String fieldName,
			@Param("promptVersion") int promptVersion);

	@Query("""
			SELECT CASE WHEN COUNT(e) > 0 THEN TRUE ELSE FALSE END
			FROM PosFieldExtractionEntity e
			WHERE e.id.documentId = :documentId
			  AND e.id.fieldName = :fieldName
			  AND e.id.promptVersion = :promptVersion
			""")
	boolean existsByDocumentIdAndFieldNameAndPromptVersion(@Param("documentId") UUID documentId,
			@Param("fieldName") String fieldName, @Param("promptVersion") int promptVersion);

	@Query("""
			SELECT e FROM PosFieldExtractionEntity e
			WHERE e.id.documentId = :documentId
			  AND e.id.promptVersion = :promptVersion
			ORDER BY e.id.fieldName ASC
			""")
	List<PosFieldExtractionEntity> findByDocumentIdAndPromptVersion(@Param("documentId") UUID documentId,
			@Param("promptVersion") int promptVersion);

	/**
	 * Counts the structured extraction outcomes for all documents belonging to
	 * the given POS record at the given prompt version. Joins through
	 * {@code PosDocumentEntity} but returns a scalar count only.
	 */
	@Query("""
			SELECT COUNT(e) FROM PosFieldExtractionEntity e
			JOIN e.document d
			WHERE d.posRecord.id = :posRecordId
			  AND e.id.promptVersion = :promptVersion
			""")
	long countByPosRecordIdAndPromptVersion(@Param("posRecordId") UUID posRecordId,
			@Param("promptVersion") int promptVersion);

}
