package horse.sumomo.pos_doc_backend.content;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import horse.sumomo.pos_doc_backend.content.DocumentContentException.Code;
import horse.sumomo.pos_doc_backend.infrastructure.minio.MinioObjectStorage;
import horse.sumomo.pos_doc_backend.infrastructure.minio.ObjectStorageException;
import horse.sumomo.pos_doc_backend.persistence.entity.PosDocumentEntity;
import horse.sumomo.pos_doc_backend.persistence.entity.PosRecordEntity;
import horse.sumomo.pos_doc_backend.persistence.entity.StorageObjectEntity;
import horse.sumomo.pos_doc_backend.persistence.repository.PosDocumentRepository;
import horse.sumomo.pos_doc_backend.persistence.repository.PosRecordRepository;

/**
 * Streams a protected extracted PDF or the original ZIP archive.
 *
 * <p>Authorization/IDOR protection happens in the lookup: a PDF is resolved only
 * when it belongs to the exact active parent record (a document under another
 * record is indistinguishable from a missing one), and a ZIP resolves only the
 * persisted source archive of an active record. The object key is never taken from
 * the request.
 *
 * <p>The lookup runs in a short read-only transaction that produces an immutable
 * {@link ContentDescriptor}; the transaction ends before any object-store contact.
 * Streaming reuses {@link MinioObjectStorage} and never loads the whole object into
 * memory or creates a public URL.
 */
@Service
public class DocumentContentService {

	private static final Logger log = LoggerFactory.getLogger(DocumentContentService.class);

	private static final String PDF = "application/pdf";
	private static final String ZIP = "application/zip";

	private final PosRecordRepository posRecordRepository;
	private final PosDocumentRepository posDocumentRepository;
	private final MinioObjectStorage storage;

	public DocumentContentService(PosRecordRepository posRecordRepository,
			PosDocumentRepository posDocumentRepository, MinioObjectStorage storage) {
		this.posRecordRepository = Objects.requireNonNull(posRecordRepository);
		this.posDocumentRepository = Objects.requireNonNull(posDocumentRepository);
		this.storage = Objects.requireNonNull(storage);
	}

	/**
	 * Produces an immutable PDF descriptor, or {@code 404 DOCUMENT_NOT_FOUND} when
	 * the record or document is missing, deleted, or the document belongs to another
	 * record.
	 */
	@Transactional(readOnly = true)
	public ContentDescriptor pdfDescriptor(UUID posRecordId, UUID documentId) {
		PosDocumentEntity document = this.posDocumentRepository
				.findActiveDocumentWithStorageByRecordAndId(posRecordId, documentId)
				.orElseThrow(() -> new DocumentContentException(Code.DOCUMENT_NOT_FOUND));
		StorageObjectEntity storageObject = document.getStorageObject();
		requireContentType(storageObject.getContentType(), PDF);
		return descriptor(storageObject);
	}

	/**
	 * Produces an immutable source-archive descriptor, or
	 * {@code 404 POS_RECORD_NOT_FOUND} when the record is missing or deleted.
	 */
	@Transactional(readOnly = true)
	public ContentDescriptor sourceArchiveDescriptor(UUID posRecordId) {
		PosRecordEntity record = this.posRecordRepository.findActiveRecordWithSourceArchive(posRecordId)
				.orElseThrow(() -> new DocumentContentException(Code.POS_RECORD_NOT_FOUND));
		StorageObjectEntity archive = record.getSourceArchive();
		requireContentType(archive.getContentType(), ZIP);
		return descriptor(archive);
	}

	/**
	 * Streams the object to the target outside any database transaction. A missing
	 * object is a sanitized {@code 500 DOCUMENT_CONTENT_UNAVAILABLE}; a temporary
	 * object-store failure is a sanitized {@code 503 DOCUMENT_STORAGE_UNAVAILABLE}.
	 * The object stream is always closed.
	 */
	public void streamContent(ContentDescriptor descriptor, OutputStream target) {
		InputStream source;
		try {
			source = this.storage.get(descriptor.objectKey());
		}
		catch (ObjectStorageException.MissingObjectException e) {
			log.warn("content: object not found (category=document-content-unavailable, storageObject={})",
					descriptor.storageObjectId());
			throw new DocumentContentException(Code.DOCUMENT_CONTENT_UNAVAILABLE, e);
		}
		catch (ObjectStorageException e) {
			log.warn("content: object store unavailable (category=document-storage-unavailable, storageObject={})",
					descriptor.storageObjectId());
			throw new DocumentContentException(Code.DOCUMENT_STORAGE_UNAVAILABLE, e);
		}
		try (source) {
			source.transferTo(target);
		}
		catch (IOException e) {
			log.warn("content: stream interrupted (category=document-storage-unavailable, storageObject={})",
					descriptor.storageObjectId());
			throw new DocumentContentException(Code.DOCUMENT_STORAGE_UNAVAILABLE, e);
		}
	}

	private static ContentDescriptor descriptor(StorageObjectEntity storageObject) {
		return new ContentDescriptor(storageObject.getId().toString(), storageObject.getObjectKey(),
				storageObject.getContentType(), storageObject.getOriginalFilename(), storageObject.getByteSize());
	}

	private static void requireContentType(String contentType, String expected) {
		if (!expected.equalsIgnoreCase(contentType)) {
			throw new DocumentContentException(Code.DOCUMENT_CONTENT_UNAVAILABLE);
		}
	}

}
