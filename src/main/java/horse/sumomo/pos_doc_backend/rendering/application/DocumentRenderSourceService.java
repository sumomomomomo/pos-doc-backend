package horse.sumomo.pos_doc_backend.rendering.application;

import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import horse.sumomo.pos_doc_backend.persistence.entity.PosDocumentEntity;
import horse.sumomo.pos_doc_backend.persistence.repository.PosDocumentRepository;
import horse.sumomo.pos_doc_backend.rendering.api.FirstPageRenderingProperties;
import horse.sumomo.pos_doc_backend.rendering.model.DocumentRenderSource;
import horse.sumomo.pos_doc_backend.rendering.service.RenderingException;

/**
 * Loads the metadata required to render one persisted PDF inside a short
 * read-only SQLite transaction.
 *
 * <p>The transaction is finished before any MinIO or PDFBox operation begins.
 * The returned {@link DocumentRenderSource} is an immutable snapshot that
 * contains no JPA entities, lazy proxies, filenames, or policy metadata.
 */
@Service
public class DocumentRenderSourceService {

	private static final Logger log = LoggerFactory.getLogger(DocumentRenderSourceService.class);

	private static final String PDF_CONTENT_TYPE = "application/pdf";

	private final PosDocumentRepository posDocumentRepository;
	private final FirstPageRenderingProperties properties;

	public DocumentRenderSourceService(PosDocumentRepository posDocumentRepository,
			FirstPageRenderingProperties properties) {
		this.posDocumentRepository = posDocumentRepository;
		this.properties = properties;
	}

	/**
	 * Loads and validates the render source for one document.
	 *
	 * @param documentId the document UUID
	 * @return an immutable {@link DocumentRenderSource} snapshot
	 * @throws RenderingException with {@link RenderingException.Code#DOCUMENT_NOT_FOUND}
	 *             when the document does not exist,
	 *             {@link RenderingException.Code#DOCUMENT_DELETED} when the
	 *             parent POS record is soft-deleted, or
	 *             {@link RenderingException.Code#PDF_METADATA_INVALID} when
	 *             the persisted content type, size, or hash is invalid
	 */
	@Transactional(readOnly = true)
	public DocumentRenderSource load(UUID documentId) {
		if (documentId == null) {
			throw new RenderingException(RenderingException.Code.DOCUMENT_NOT_FOUND);
		}

		PosDocumentEntity document = this.posDocumentRepository.findWithAssociationsById(documentId)
				.orElseThrow(() -> {
					log.debug("Render source lookup: document not found; documentId={}", documentId);
					return new RenderingException(RenderingException.Code.DOCUMENT_NOT_FOUND);
				});

		if (document.getPosRecord().getDeletedAt() != null) {
			log.debug("Render source lookup: parent POS record is soft-deleted; documentId={}", documentId);
			throw new RenderingException(RenderingException.Code.DOCUMENT_DELETED);
		}

		var storage = document.getStorageObject();
		String contentType = storage.getContentType();
		if (contentType == null || !PDF_CONTENT_TYPE.equalsIgnoreCase(contentType.trim())) {
			log.debug("Render source lookup: content type is not application/pdf; documentId={}", documentId);
			throw new RenderingException(RenderingException.Code.PDF_METADATA_INVALID);
		}

		long byteSize = storage.getByteSize();
		if (byteSize <= 0 || byteSize > this.properties.maxPdfBytes()) {
			log.debug("Render source lookup: declared byte size is invalid; documentId={}", documentId);
			throw new RenderingException(RenderingException.Code.PDF_METADATA_INVALID);
		}

		String sha256 = storage.getSha256();
		if (sha256 == null || !sha256.matches("[0-9a-fA-F]{64}")) {
			log.debug("Render source lookup: SHA-256 is not 64 hex characters; documentId={}", documentId);
			throw new RenderingException(RenderingException.Code.PDF_METADATA_INVALID);
		}

		String objectKey = storage.getObjectKey();
		return new DocumentRenderSource(document.getPosRecord().getId(), document.getId(),
				storage.getId(), objectKey, byteSize, sha256.toLowerCase(Locale.ROOT));
	}

}
