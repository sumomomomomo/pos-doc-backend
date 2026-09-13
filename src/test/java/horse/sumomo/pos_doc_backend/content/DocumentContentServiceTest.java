package horse.sumomo.pos_doc_backend.content;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.minio.MinioClient;
import io.minio.MakeBucketArgs;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.utility.DockerImageName;

import horse.sumomo.pos_doc_backend.infrastructure.minio.MinioObjectStorage;
import horse.sumomo.pos_doc_backend.infrastructure.minio.ObjectStorageException;
import horse.sumomo.pos_doc_backend.persistence.entity.PosDocumentEntity;
import horse.sumomo.pos_doc_backend.persistence.entity.PosRecordEntity;
import horse.sumomo.pos_doc_backend.persistence.entity.StorageObjectEntity;
import horse.sumomo.pos_doc_backend.persistence.model.DocumentProcessingStatus;
import horse.sumomo.pos_doc_backend.persistence.model.DocumentType;
import horse.sumomo.pos_doc_backend.persistence.model.PosRecordStatus;
import horse.sumomo.pos_doc_backend.persistence.repository.PosDocumentRepository;
import horse.sumomo.pos_doc_backend.persistence.repository.PosRecordRepository;
import horse.sumomo.pos_doc_backend.persistence.repository.StorageObjectRepository;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

/**
 * Content-access persistence and storage tests (Task 11 content-access tests 1-8,
 * excluding 9 which is proven by the security filter-chain test). Uses a real
 * Testcontainers MinIO and a temporary SQLite database with small synthetic
 * PDF/ZIP byte sequences (no real insurance files, no PII).
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DocumentContentServiceTest {

	private static final DockerImageName MINIO_IMAGE =
			DockerImageName.parse("cgr.dev/chainguard/minio:latest").asCompatibleSubstituteFor("minio/minio");
	private static final String TEST_BUCKET = "pos-doc-content-test";
	private static final byte[] PDF_BYTES =
			("%PDF-1.4\n% synthetic document\n1 0 obj\n<< /Type /Catalog >>\nendobj\n%%EOF\n")
					.getBytes(StandardCharsets.UTF_8);
	private static final byte[] ZIP_BYTES = new byte[] {0x50, 0x4b, 0x03, 0x04, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e};

	private static MinIOContainer minio;
	private static MinioClient adminClient;

	@Autowired
	private DocumentContentService contentService;
	@Autowired
	private MinioObjectStorage storage;
	@Autowired
	private PosRecordRepository posRecordRepository;
	@Autowired
	private PosDocumentRepository posDocumentRepository;
	@Autowired
	private StorageObjectRepository storageObjectRepository;

	@DynamicPropertySource
	static void containerProperties(DynamicPropertyRegistry registry) throws Exception {
		minio = new MinIOContainer(MINIO_IMAGE).withUserName("content-access-key")
				.withPassword("content-secret-key-change-me");
		minio.start();
		adminClient = MinioClient.builder().endpoint(minio.getS3URL())
				.credentials(minio.getUserName(), minio.getPassword()).build();
		adminClient.makeBucket(MakeBucketArgs.builder().bucket(TEST_BUCKET).build());
		registry.add("storage.minio.endpoint", minio::getS3URL);
		registry.add("storage.minio.access-key", minio::getUserName);
		registry.add("storage.minio.secret-key", minio::getPassword);
		registry.add("storage.minio.bucket", () -> TEST_BUCKET);
		Path db = Files.createTempFile("pos-doc-content-test", ".db");
		db.toFile().deleteOnExit();
		Path.of(db.toString() + "-wal").toFile().deleteOnExit();
		Path.of(db.toString() + "-shm").toFile().deleteOnExit();
		registry.add("SQLITE_URL", () -> "jdbc:sqlite:" + db);
	}

	@AfterAll
	static void stopContainer() throws Exception {
		if (minio != null && minio.isRunning()) {
			minio.stop();
		}
		if (adminClient != null) {
			adminClient.close();
		}
	}

	// 1. Active PDF content streams byte-for-byte with the correct length and type.
	@Test
	void activePdfStreamsByteForByteWithCorrectDescriptor() {
		Fixture f = fixture("pdf-active");
		this.storage.put(f.pdfKey, new java.io.ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length, "application/pdf");

		ContentDescriptor descriptor = this.contentService.pdfDescriptor(f.recordId, f.docId);
		assertEquals("application/pdf", descriptor.contentType());
		assertEquals(PDF_BYTES.length, descriptor.expectedByteSize());
		assertEquals("document.pdf", descriptor.originalFilename());

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		this.contentService.streamContent(descriptor, out);
		assertArrayEquals(PDF_BYTES, out.toByteArray(), "PDF must stream byte-for-byte");
	}

	// 2. Active ZIP streams byte-for-byte as an attachment (zip content type).
	@Test
	void activeZipStreamsByteForByteWithZipDescriptor() {
		Fixture f = fixture("zip-active");
		this.storage.put(f.zipKey, new java.io.ByteArrayInputStream(ZIP_BYTES), ZIP_BYTES.length, "application/zip");

		ContentDescriptor descriptor = this.contentService.sourceArchiveDescriptor(f.recordId);
		assertEquals("application/zip", descriptor.contentType());
		assertEquals(ZIP_BYTES.length, descriptor.expectedByteSize());
		assertEquals("archive.zip", descriptor.originalFilename());

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		this.contentService.streamContent(descriptor, out);
		assertArrayEquals(ZIP_BYTES, out.toByteArray(), "ZIP must stream byte-for-byte");
	}

	// 3. Deleted parent, missing document, and wrong parent are indistinguishable 404s.
	@Test
	void deletedParentMissingDocAndWrongParentAreIndistinguishable404() {
		Fixture a = fixture("case-a");
		Fixture b = fixture("case-b");
		this.storage.put(a.pdfKey, new java.io.ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length, "application/pdf");

		// A valid pairing succeeds.
		assertDoesNotThrow(() -> this.contentService.pdfDescriptor(a.recordId, a.docId));

		// Delete record A; its document becomes indistinguishable from a missing one.
		PosRecordEntity aEntity = this.posRecordRepository.findById(a.recordId).orElseThrow();
		aEntity.markDeleted(Instant.now());
		this.posRecordRepository.saveAndFlush(aEntity);

		DocumentContentException deletedParent = assertThrows(DocumentContentException.class,
				() -> this.contentService.pdfDescriptor(a.recordId, a.docId));
		DocumentContentException wrongParent = assertThrows(DocumentContentException.class,
				() -> this.contentService.pdfDescriptor(b.recordId, a.docId));
		DocumentContentException missingDoc = assertThrows(DocumentContentException.class,
				() -> this.contentService.pdfDescriptor(b.recordId, UUID.randomUUID()));

		assertEquals(DocumentContentException.Code.DOCUMENT_NOT_FOUND, deletedParent.getCode());
		assertEquals(DocumentContentException.Code.DOCUMENT_NOT_FOUND, wrongParent.getCode());
		assertEquals(DocumentContentException.Code.DOCUMENT_NOT_FOUND, missingDoc.getCode());
	}

	// Deleted parent ZIP is a POS_RECORD_NOT_FOUND (a distinct, sanitized 404).
	@Test
	void deletedParentZipIsPosRecordNotFound() {
		Fixture f = fixture("zip-deleted");
		PosRecordEntity entity = this.posRecordRepository.findById(f.recordId).orElseThrow();
		entity.markDeleted(Instant.now());
		this.posRecordRepository.saveAndFlush(entity);

		DocumentContentException e = assertThrows(DocumentContentException.class,
				() -> this.contentService.sourceArchiveDescriptor(f.recordId));
		assertEquals(DocumentContentException.Code.POS_RECORD_NOT_FOUND, e.getCode());
	}

	// 7. A missing/inconsistent object maps to a sanitized 500.
	@Test
	void missingObjectMapsToContentUnavailable500() {
		Fixture f = fixture("missing-object");
		this.storage.put(f.pdfKey, new java.io.ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length, "application/pdf");
		ContentDescriptor descriptor = this.contentService.pdfDescriptor(f.recordId, f.docId);
		this.storage.delete(f.pdfKey);

		DocumentContentException e = assertThrows(DocumentContentException.class,
				() -> this.contentService.streamContent(descriptor, new ByteArrayOutputStream()));
		assertEquals(DocumentContentException.Code.DOCUMENT_CONTENT_UNAVAILABLE, e.getCode());
		assertEquals(500, e.getCode().httpStatus());
	}

	// 7. A temporary MinIO failure maps to a sanitized 503.
	@Test
	void temporaryMinioFailureMapsToStorageUnavailable503() {
		MinioObjectStorage failing = new MinioObjectStorage(null, null) {
			@Override
			public java.io.InputStream get(String objectKey) {
				throw new ObjectStorageException("simulated connectivity failure", new RuntimeException("boom"));
			}
		};
		DocumentContentService service = new DocumentContentService(this.posRecordRepository,
				this.posDocumentRepository, failing);
		ContentDescriptor descriptor = new ContentDescriptor("so-x", "archives/x/y.pdf", "application/pdf",
				"document.pdf", PDF_BYTES.length);

		DocumentContentException e = assertThrows(DocumentContentException.class,
				() -> service.streamContent(descriptor, new ByteArrayOutputStream()));
		assertEquals(DocumentContentException.Code.DOCUMENT_STORAGE_UNAVAILABLE, e.getCode());
		assertEquals(503, e.getCode().httpStatus());
	}

	// 8. Errors contain no object key, bucket, or credentials.
	@Test
	void errorsExposeNoObjectKeyBucketOrCredentials() {
		Fixture f = fixture("no-leak");
		this.storage.put(f.pdfKey, new java.io.ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length, "application/pdf");
		ContentDescriptor descriptor = this.contentService.pdfDescriptor(f.recordId, f.docId);
		this.storage.delete(f.pdfKey);

		DocumentContentException e = assertThrows(DocumentContentException.class,
				() -> this.contentService.streamContent(descriptor, new ByteArrayOutputStream()));
		String rendered = e.getMessage() + " | " + e.getCode();
		assertFalse(rendered.contains(f.pdfKey), "error must not leak the object key: " + rendered);
		assertFalse(rendered.contains(TEST_BUCKET), "error must not leak the bucket: " + rendered);
		assertFalse(rendered.contains("content-access-key"), "error must not leak credentials: " + rendered);
	}

	// 5. MinIO is contacted with no active SQLite transaction.
	@Test
	void minioIsContactedWithoutAnActiveDatabaseTransaction() {
		Fixture f = fixture("tx-check");
		this.storage.put(f.pdfKey, new java.io.ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length, "application/pdf");
		ContentDescriptor descriptor = this.contentService.pdfDescriptor(f.recordId, f.docId);

		MinioObjectStorage spy = spy(this.storage);
		java.util.concurrent.atomic.AtomicBoolean sawActiveTransaction = new java.util.concurrent.atomic.AtomicBoolean(false);
		doAnswer(invocation -> {
			sawActiveTransaction.set(org.springframework.transaction.support.TransactionSynchronizationManager
					.isActualTransactionActive());
			return invocation.callRealMethod();
		}).when(spy).get(ArgumentMatchers.anyString());

		DocumentContentService service = new DocumentContentService(this.posRecordRepository,
				this.posDocumentRepository, spy);
		service.streamContent(descriptor, new ByteArrayOutputStream());
		assertFalse(sawActiveTransaction.get(), "MinIO must not be contacted inside an active SQLite transaction");
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	/**
	 * Persists a record with a ZIP source archive and one PDF document, and returns
	 * the identifiers plus the (not-yet-uploaded) object keys.
	 */
	private Fixture fixture(String tag) {
		StorageObjectEntity zip = this.storageObjectRepository.saveAndFlush(new StorageObjectEntity(UUID.randomUUID(),
				"archives/" + tag + "-" + UUID.randomUUID(), "archive.zip", "application/zip", ZIP_BYTES.length,
				ReviewSha.SHA, Instant.now()));
		PosRecordEntity record = this.posRecordRepository.saveAndFlush(new PosRecordEntity(UUID.randomUUID(), zip,
				PosRecordStatus.REVIEW_REQUIRED, "google:content-test-subject", Instant.now(), Instant.now()));
		StorageObjectEntity pdf = this.storageObjectRepository.saveAndFlush(new StorageObjectEntity(UUID.randomUUID(),
				"archives/" + tag + "/" + UUID.randomUUID() + ".pdf", "document.pdf", "application/pdf",
				PDF_BYTES.length, ReviewSha.SHA, Instant.now()));
		PosDocumentEntity document = this.posDocumentRepository.saveAndFlush(new PosDocumentEntity(UUID.randomUUID(),
				record, pdf, 0L, DocumentType.OTHER, DocumentProcessingStatus.COMPLETED));
		return new Fixture(record.getId(), document.getId(), pdf.getObjectKey(), zip.getObjectKey());
	}

	private record Fixture(UUID recordId, UUID docId, String pdfKey, String zipKey) {
	}

	/** Small holder so this test does not depend on the review package fixtures. */
	private static final class ReviewSha {
		static final String SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
	}

}
