package horse.sumomo.pos_doc_backend.persistence.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import horse.sumomo.pos_doc_backend.persistence.entity.DocumentOcrResultEntity;
import horse.sumomo.pos_doc_backend.persistence.entity.DocumentOcrResultId;

/**
 * Repository for durable per-document OCR results.
 *
 * <p>All queries use explicit JPQL because the composite key fields
 * ({@code documentId}, {@code promptVersion}) are nested inside the
 * {@link DocumentOcrResultId} embedded ID and cannot be resolved by
 * Spring Data's derived query parser.
 *
 * <p>The count query joins through {@code PosDocumentEntity} to reach the
 * POS record but selects only a scalar count, so it never loads OCR text
 * entities merely to count rows.
 */
public interface DocumentOcrResultRepository extends JpaRepository<DocumentOcrResultEntity, DocumentOcrResultId> {

	@Query("""
			SELECT r FROM DocumentOcrResultEntity r
			WHERE r.id.documentId = :documentId
			  AND r.id.promptVersion = :promptVersion
			""")
	Optional<DocumentOcrResultEntity> findByDocumentIdAndPromptVersion(@Param("documentId") UUID documentId,
			@Param("promptVersion") int promptVersion);

	@Query("""
			SELECT CASE WHEN COUNT(r) > 0 THEN TRUE ELSE FALSE END
			FROM DocumentOcrResultEntity r
			WHERE r.id.documentId = :documentId
			  AND r.id.promptVersion = :promptVersion
			""")
	boolean existsByDocumentIdAndPromptVersion(@Param("documentId") UUID documentId,
			@Param("promptVersion") int promptVersion);

	/**
	 * Counts the version-1 (or any given prompt-version) OCR results for
	 * all documents belonging to the given POS record. Joins through
	 * {@code PosDocumentEntity} but returns a scalar count only — no OCR
	 * text is loaded.
	 */
	@Query("""
			SELECT COUNT(r) FROM DocumentOcrResultEntity r
			JOIN r.document d
			WHERE d.posRecord.id = :posRecordId
			  AND r.id.promptVersion = :promptVersion
			""")
	long countByPosRecordIdAndPromptVersion(@Param("posRecordId") UUID posRecordId,
			@Param("promptVersion") int promptVersion);

}
