package horse.sumomo.pos_doc_backend.rendering.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import horse.sumomo.pos_doc_backend.persistence.entity.PosDocumentEntity;
import horse.sumomo.pos_doc_backend.persistence.entity.PosRecordEntity;
import horse.sumomo.pos_doc_backend.persistence.entity.StorageObjectEntity;
import horse.sumomo.pos_doc_backend.persistence.model.DocumentProcessingStatus;
import horse.sumomo.pos_doc_backend.persistence.model.DocumentType;
import horse.sumomo.pos_doc_backend.persistence.model.PosRecordStatus;
import horse.sumomo.pos_doc_backend.persistence.repository.PosDocumentRepository;
import horse.sumomo.pos_doc_backend.persistence.repository.PosRecordRepository;
import horse.sumomo.pos_doc_backend.persistence.repository.StorageObjectRepository;
import horse.sumomo.pos_doc_backend.rendering.model.DocumentRenderSource;
import horse.sumomo.pos_doc_backend.rendering.service.RenderingException;

/**
 * Integration tests for {@link DocumentRenderSourceService} using the real
 * migrated SQLite schema.
 */
@SpringBootTest(properties = {
		"app.messaging.outbox.enabled=false",
		"app.ingestion.consumer.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DocumentRenderSourceServiceIntegrationTest {

	private static final String TEST_SHA = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";

	@Autowired
	private DocumentRenderSourceService service;

	@Autowired
	private StorageObjectRepository storageObjectRepository;

	@Autowired
	private PosRecordRepository posRecordRepository;

	@Autowired
	private PosDocumentRepository posDocumentRepository;

	@DynamicPropertySource
	static void sqliteUrl(DynamicPropertyRegistry registry) throws Exception {
		Path dbFile = Files.createTempFile("pos-doc-render-source-test", ".db");
		dbFile.toFile().deleteOnExit();
		Path.of(dbFile.toString() + "-wal").toFile().deleteOnExit();
		Path.of(dbFile.toString() + "-shm").toFile().deleteOnExit();
		registry.add("SQLITE_URL", () -> "jdbc:sqlite:" + dbFile);
	}

	@Test
	void existingActiveDocumentReturnsExactImmutableSnapshot() {
		StorageObjectEntity archive = saveArchive("render-src/archive");
		PosRecordEntity record = saveRecord(archive);
		StorageObjectEntity pdf = savePdfObject("render-src/doc.pdf", 2048L, TEST_SHA);
		UUID documentId = saveDocument(record, pdf);

		DocumentRenderSource source = this.service.load(documentId);

		assertEquals(record.getId(), source.posRecordId());
		assertEquals(documentId, source.documentId());
		assertEquals(pdf.getId(), source.storageObjectId());
		assertEquals("render-src/doc.pdf", source.objectKey());
		assertEquals(2048L, source.expectedByteSize());
		assertEquals(TEST_SHA, source.expectedSha256());
		// No lazy proxy: fields are usable after the transaction ends.
		assertNotNull(source.objectKey());
		assertNotNull(source.expectedSha256());
	}

	@Test
	void missingDocumentReturnsDocumentNotFound() {
		UUID missingId = UUID.randomUUID();
		RenderingException e = assertThrows(RenderingException.class, () -> this.service.load(missingId));
		assertEquals(RenderingException.Code.DOCUMENT_NOT_FOUND, e.getCode());
	}

	@Test
	void softDeletedParentReturnsDocumentDeleted() {
		StorageObjectEntity archive = saveArchive("render-deleted/archive");
		PosRecordEntity record = saveRecord(archive);
		record.markDeleted(Instant.now());
		this.posRecordRepository.saveAndFlush(record);
		StorageObjectEntity pdf = savePdfObject("render-deleted/doc.pdf", 1024L, TEST_SHA);
		UUID documentId = saveDocument(record, pdf);

		RenderingException e = assertThrows(RenderingException.class, () -> this.service.load(documentId));
		assertEquals(RenderingException.Code.DOCUMENT_DELETED, e.getCode());
	}

	@Test
	void nonPdfContentTypeReturnsPdfMetadataInvalid() {
		StorageObjectEntity archive = saveArchive("render-nonpdf/archive");
		PosRecordEntity record = saveRecord(archive);
		StorageObjectEntity txt = saveObject("render-nonpdf/doc.txt", "text/plain", 1024L, TEST_SHA);
		UUID documentId = saveDocument(record, txt);

		RenderingException e = assertThrows(RenderingException.class, () -> this.service.load(documentId));
		assertEquals(RenderingException.Code.PDF_METADATA_INVALID, e.getCode());
	}

	@Test
	void zeroByteSizeReturnsPdfMetadataInvalid() {
		StorageObjectEntity archive = saveArchive("render-zerosize/archive");
		PosRecordEntity record = saveRecord(archive);
		StorageObjectEntity pdf = saveObject("render-zerosize/doc.pdf", "application/pdf", 0L, TEST_SHA);
		UUID documentId = saveDocument(record, pdf);

		RenderingException e = assertThrows(RenderingException.class, () -> this.service.load(documentId));
		assertEquals(RenderingException.Code.PDF_METADATA_INVALID, e.getCode());
	}

	@Test
	void excessiveDeclaredSizeReturnsPdfMetadataInvalid() {
		StorageObjectEntity archive = saveArchive("render-bigsize/archive");
		PosRecordEntity record = saveRecord(archive);
		// 50 MiB + 1 exceeds the default max-pdf-bytes of 50 MiB.
		StorageObjectEntity pdf = saveObject("render-bigsize/doc.pdf", "application/pdf", 52428801L, TEST_SHA);
		UUID documentId = saveDocument(record, pdf);

		RenderingException e = assertThrows(RenderingException.class, () -> this.service.load(documentId));
		assertEquals(RenderingException.Code.PDF_METADATA_INVALID, e.getCode());
	}

	@Test
	void uppercaseSha256IsNormalizedToLowercase() {
		StorageObjectEntity archive = saveArchive("render-upper/archive");
		PosRecordEntity record = saveRecord(archive);
		String upperSha = TEST_SHA.toUpperCase();
		StorageObjectEntity pdf = saveObject("render-upper/doc.pdf", "application/pdf", 1024L, upperSha);
		UUID documentId = saveDocument(record, pdf);

		DocumentRenderSource source = this.service.load(documentId);
		assertEquals(TEST_SHA, source.expectedSha256(), "SHA-256 must be normalized to lowercase");
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	private StorageObjectEntity saveArchive(String objectKey) {
		StorageObjectEntity archive = new StorageObjectEntity(UUID.randomUUID(), objectKey,
				"archive.zip", "application/zip", 256L, TEST_SHA, Instant.ofEpochMilli(1_700_000_000_000L));
		return this.storageObjectRepository.saveAndFlush(archive);
	}

	private StorageObjectEntity savePdfObject(String objectKey, long byteSize, String sha256) {
		return saveObject(objectKey, "application/pdf", byteSize, sha256);
	}

	private StorageObjectEntity saveObject(String objectKey, String contentType, long byteSize, String sha256) {
		StorageObjectEntity obj = new StorageObjectEntity(UUID.randomUUID(), objectKey,
				"file", contentType, byteSize, sha256, Instant.ofEpochMilli(1_700_000_000_000L));
		return this.storageObjectRepository.saveAndFlush(obj);
	}

	private PosRecordEntity saveRecord(StorageObjectEntity archive) {
		PosRecordEntity record = new PosRecordEntity(UUID.randomUUID(), archive,
				PosRecordStatus.UPLOADED, "tester-subject",
				Instant.ofEpochMilli(1_700_000_000_000L), Instant.ofEpochMilli(1_700_000_000_000L));
		return this.posRecordRepository.saveAndFlush(record);
	}

	private UUID saveDocument(PosRecordEntity record, StorageObjectEntity pdf) {
		UUID documentId = UUID.randomUUID();
		this.posDocumentRepository.saveAndFlush(new PosDocumentEntity(documentId, record, pdf, 0,
				DocumentType.OTHER, DocumentProcessingStatus.PENDING));
		return documentId;
	}

}
