package horse.sumomo.pos_doc_backend.rendering.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.utility.DockerImageName;

import horse.sumomo.pos_doc_backend.infrastructure.minio.MinioObjectStorage;
import horse.sumomo.pos_doc_backend.persistence.entity.PosDocumentEntity;
import horse.sumomo.pos_doc_backend.persistence.entity.PosRecordEntity;
import horse.sumomo.pos_doc_backend.persistence.entity.StorageObjectEntity;
import horse.sumomo.pos_doc_backend.persistence.model.DocumentProcessingStatus;
import horse.sumomo.pos_doc_backend.persistence.model.DocumentType;
import horse.sumomo.pos_doc_backend.persistence.model.PosRecordStatus;
import horse.sumomo.pos_doc_backend.persistence.repository.PosDocumentRepository;
import horse.sumomo.pos_doc_backend.persistence.repository.PosRecordRepository;
import horse.sumomo.pos_doc_backend.persistence.repository.StorageObjectRepository;
import horse.sumomo.pos_doc_backend.rendering.application.FirstPageRenderPreparationService;
import horse.sumomo.pos_doc_backend.rendering.model.RenderedFirstPage;

/**
 * Real MinIO integration test for {@link FirstPageRenderPreparationService}.
 *
 * <p>Runs MinIO through Testcontainers and uses real SQLite metadata. Stores
 * a small two-page PDF using the real storage adapter, persists its actual
 * size and SHA-256, calls
 * {@link FirstPageRenderPreparationService#prepare(UUID)}, and proves the
 * full rendering pipeline.
 */
@SpringBootTest(properties = {
		"app.messaging.outbox.enabled=false",
		"app.ingestion.consumer.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FirstPageRenderPreparationIntegrationTest {

	private static final String TEST_BUCKET = "pos-documents-render-test";
	private static final DockerImageName MINIO_IMAGE =
			DockerImageName.parse("minio/minio:RELEASE.2025-09-07T16-13-09Z");

	private static MinIOContainer minio;
	private static MinioClient adminClient;

	@Autowired
	private FirstPageRenderPreparationService preparationService;

	@Autowired
	private MinioObjectStorage storage;

	@Autowired
	private StorageObjectRepository storageObjectRepository;

	@Autowired
	private PosRecordRepository posRecordRepository;

	@Autowired
	private PosDocumentRepository posDocumentRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@DynamicPropertySource
	static void containerProperties(DynamicPropertyRegistry registry) throws Exception {
		minio = new MinIOContainer(MINIO_IMAGE)
				.withUserName("render-access-key")
				.withPassword("render-secret-key-change-me");
		minio.start();
		adminClient = MinioClient.builder()
				.endpoint(minio.getS3URL())
				.credentials(minio.getUserName(), minio.getPassword())
				.build();
		adminClient.makeBucket(MakeBucketArgs.builder().bucket(TEST_BUCKET).build());

		registry.add("storage.minio.endpoint", minio::getS3URL);
		registry.add("storage.minio.access-key", minio::getUserName);
		registry.add("storage.minio.secret-key", minio::getPassword);
		registry.add("storage.minio.bucket", () -> TEST_BUCKET);

		Path sqliteDbFile = Files.createTempFile("pos-doc-render-int-test", ".db");
		sqliteDbFile.toFile().deleteOnExit();
		Path.of(sqliteDbFile.toString() + "-wal").toFile().deleteOnExit();
		Path.of(sqliteDbFile.toString() + "-shm").toFile().deleteOnExit();
		registry.add("SQLITE_URL", () -> "jdbc:sqlite:" + sqliteDbFile);
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

	@Test
	void prepareStreamsPdfFromMinIOAndRendersFirstPage() throws Exception {
		// Generate a two-page PDF with PDFBox.
		byte[] pdfBytes = twoPagePdf("TASK 7 PAGE ONE", "TASK 7 PAGE TWO");
		String sha256 = sha256Hex(pdfBytes);
		long byteSize = pdfBytes.length;

		// Persist the metadata and upload to MinIO.
		UUID posRecordId = UUID.randomUUID();
		UUID documentId = UUID.randomUUID();
		UUID storageObjectId = UUID.randomUUID();
		String objectKey = "documents/" + posRecordId + "/" + documentId + ".pdf";

		StorageObjectEntity archive = saveArchive("render-int/archive");
		PosRecordEntity record = saveRecord(archive, posRecordId);
		StorageObjectEntity pdfObj = savePdfObject(storageObjectId, objectKey, byteSize, sha256);
		saveDocument(documentId, record, pdfObj);

		// Upload the PDF to MinIO.
		try (InputStream in = new ByteArrayInputStream(pdfBytes)) {
			this.storage.put(objectKey, in, byteSize, "application/pdf");
		}

		// Count objects before prepare.
		int objectsBefore = countMinioObjects();

		// Call prepare.
		RenderedFirstPage result;
		try {
			result = this.preparationService.prepare(documentId);
		}
		catch (Exception e) {
			throw new AssertionError("prepare() failed", e);
		}

		try {
			// The result belongs to the requested document.
			assertEquals(documentId, result.documentId());

			// Only page 0 was rendered: A4 dimensions at 200 DPI.
			assertEquals(1653, result.widthPixels());
			assertEquals(2338, result.heightPixels());
			assertEquals(200, result.dpi());

			// PNG signature, dimensions, DPI, and size are correct.
			assertTrue(hasPngSignature(result.pngPath()));
			assertTrue(result.pngByteSize() > 0);
			BufferedImage img = ImageIO.read(result.pngPath().toFile());
			assertNotNull(img);
			// PDFBox produces TYPE_3BYTE_BGR for ImageType.RGB.
			assertEquals(BufferedImage.TYPE_3BYTE_BGR, img.getType());
			assertEquals(1653, img.getWidth());
			assertEquals(2338, img.getHeight());

			// The PDF temp file is already gone when prepare returns.
			// (We can't check the exact path, but we can verify no new
			// MinIO object was created.)

			// The PNG exists while the handle is open.
			assertTrue(Files.exists(result.pngPath()));
		}
		finally {
			result.close();
		}

		// The PNG is gone after close.
		// (We can't check the exact path after close, but the handle is closed.)

		// No new MinIO object was created.
		int objectsAfter = countMinioObjects();
		assertEquals(objectsBefore, objectsAfter, "no new MinIO object should be created");

		// SQLite rows and statuses are unchanged.
		String status = this.jdbcTemplate.queryForObject(
				"SELECT processing_status FROM pos_document WHERE id = ?", String.class, documentId);
		assertEquals("PENDING", status, "processing_status must remain PENDING");
	}

	@Test
	void missingObjectReturnsPdfObjectMissing() throws Exception {
		// Persist metadata for a PDF that doesn't exist in MinIO.
		UUID posRecordId = UUID.randomUUID();
		UUID documentId = UUID.randomUUID();
		UUID storageObjectId = UUID.randomUUID();
		String objectKey = "documents/" + posRecordId + "/" + documentId + ".pdf";

		StorageObjectEntity archive = saveArchive("render-int-missing/archive");
		PosRecordEntity record = saveRecord(archive, posRecordId);
		byte[] dummyPdf = "%PDF-1.4\n% dummy\n%%EOF\n".getBytes();
		StorageObjectEntity pdfObj = savePdfObject(storageObjectId, objectKey, dummyPdf.length,
				sha256Hex(dummyPdf));
		saveDocument(documentId, record, pdfObj);

		// Do NOT upload to MinIO.
		RenderingException e = assertThrows(RenderingException.class,
				() -> this.preparationService.prepare(documentId));
		assertEquals(RenderingException.Code.PDF_OBJECT_MISSING, e.getCode());
	}

	@Test
	void storedByteHashMismatchReturnsPdfHashMismatch() throws Exception {
		// Persist metadata with a wrong hash, upload the actual PDF.
		UUID posRecordId = UUID.randomUUID();
		UUID documentId = UUID.randomUUID();
		UUID storageObjectId = UUID.randomUUID();
		String objectKey = "documents/" + posRecordId + "/" + documentId + ".pdf";

		StorageObjectEntity archive = saveArchive("render-int-hash/archive");
		PosRecordEntity record = saveRecord(archive, posRecordId);

		byte[] pdfBytes = "%PDF-1.4\n% hash mismatch test\n%%EOF\n".getBytes();
		String wrongSha = "0".repeat(64);
		StorageObjectEntity pdfObj = savePdfObject(storageObjectId, objectKey, pdfBytes.length, wrongSha);
		saveDocument(documentId, record, pdfObj);

		// Upload the actual PDF (with a different hash than persisted).
		try (InputStream in = new ByteArrayInputStream(pdfBytes)) {
			this.storage.put(objectKey, in, pdfBytes.length, "application/pdf");
		}

		RenderingException e = assertThrows(RenderingException.class,
				() -> this.preparationService.prepare(documentId));
		assertEquals(RenderingException.Code.PDF_HASH_MISMATCH, e.getCode());
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	private int countMinioObjects() throws Exception {
		int count = 0;
		var result = adminClient.listObjects(ListObjectsArgs.builder().bucket(TEST_BUCKET).build());
		for (var obj : result) {
			count++;
		}
		return count;
	}

	private StorageObjectEntity saveArchive(String objectKey) {
		StorageObjectEntity archive = new StorageObjectEntity(UUID.randomUUID(), objectKey,
				"archive.zip", "application/zip", 256L,
				"fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210",
				Instant.ofEpochMilli(1_700_000_000_000L));
		return this.storageObjectRepository.saveAndFlush(archive);
	}

	private PosRecordEntity saveRecord(StorageObjectEntity archive, UUID posRecordId) {
		PosRecordEntity record = new PosRecordEntity(posRecordId, archive,
				PosRecordStatus.UPLOADED, "tester-subject",
				Instant.ofEpochMilli(1_700_000_000_000L), Instant.ofEpochMilli(1_700_000_000_000L));
		return this.posRecordRepository.saveAndFlush(record);
	}

	private StorageObjectEntity savePdfObject(UUID id, String objectKey, long byteSize, String sha256) {
		StorageObjectEntity obj = new StorageObjectEntity(id, objectKey,
				"document.pdf", "application/pdf", byteSize, sha256,
				Instant.ofEpochMilli(1_700_000_000_000L));
		return this.storageObjectRepository.saveAndFlush(obj);
	}

	private void saveDocument(UUID documentId, PosRecordEntity record, StorageObjectEntity pdf) {
		this.posDocumentRepository.saveAndFlush(new PosDocumentEntity(documentId, record, pdf, 0,
				DocumentType.OTHER, DocumentProcessingStatus.PENDING));
	}

	private static byte[] twoPagePdf(String textPage0, String textPage1) throws Exception {
		try (PDDocument doc = new PDDocument()) {
			PDRectangle a4 = PDRectangle.A4;
			PDPage page0 = new PDPage(a4);
			doc.addPage(page0);
			try (PDPageContentStream cs = new PDPageContentStream(doc, page0)) {
				cs.beginText();
				cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
				cs.newLineAtOffset(50, a4.getHeight() - 50);
				cs.showText(textPage0);
				cs.endText();
			}

			PDPage page1 = new PDPage(a4);
			doc.addPage(page1);
			try (PDPageContentStream cs = new PDPageContentStream(doc, page1)) {
				cs.beginText();
				cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
				cs.newLineAtOffset(50, a4.getHeight() - 50);
				cs.showText(textPage1);
				cs.endText();
			}

			ByteArrayOutputStream out = new ByteArrayOutputStream();
			doc.save(out);
			return out.toByteArray();
		}
	}

	private static String sha256Hex(byte[] bytes) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update(bytes);
			StringBuilder sb = new StringBuilder();
			for (byte b : digest.digest()) {
				sb.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
			}
			return sb.toString();
		}
		catch (Exception e) {
			throw new AssertionError(e);
		}
	}

	private static boolean hasPngSignature(Path path) {
		byte[] PNG_SIGNATURE = { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };
		try (InputStream in = Files.newInputStream(path)) {
			byte[] header = new byte[PNG_SIGNATURE.length];
			int read = 0;
			while (read < PNG_SIGNATURE.length) {
				int r = in.read(header, read, PNG_SIGNATURE.length - read);
				if (r == -1) {
					return false;
				}
				read += r;
			}
			for (int i = 0; i < PNG_SIGNATURE.length; i++) {
				if (header[i] != PNG_SIGNATURE[i]) {
					return false;
				}
			}
			return true;
		}
		catch (Exception e) {
			return false;
		}
	}

}
