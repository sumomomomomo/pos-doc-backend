package horse.sumomo.pos_doc_backend.ingestion.consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.utility.DockerImageName;

import horse.sumomo.pos_doc_backend.ingestion.api.UploadLimitsProperties;
import horse.sumomo.pos_doc_backend.ingestion.archive.ZipArchiveValidator;
import horse.sumomo.pos_doc_backend.infrastructure.minio.MinioObjectStorage;

@SpringBootTest(properties = {
		"app.messaging.outbox.enabled=false",
		"app.ingestion.consumer.enabled=false"
})
@DirtiesContext
class ArchiveExtractionServiceIntegrationTest {

	private static final String TEST_BUCKET = "pos-documents-extractor-test";
	private static final DockerImageName MINIO_IMAGE =
			DockerImageName.parse("minio/minio:RELEASE.2025-09-07T16-13-09Z");

	private static final byte[] PDF_A = ("%PDF-1.4\n% Document A\n%%EOF\n").getBytes(StandardCharsets.UTF_8);
	private static final byte[] PDF_B = ("%PDF-1.4\n% Document B\n%%EOF\n").getBytes(StandardCharsets.UTF_8);

	private static MinIOContainer minio;
	private static MinioClient adminClient;

	@Autowired
	private ArchiveExtractionService service;

	@Autowired
	private MinioObjectStorage storage;

	@DynamicPropertySource
	static void containerProperties(DynamicPropertyRegistry registry) throws Exception {
		minio = new MinIOContainer(MINIO_IMAGE)
				.withUserName("extractor-access-key")
				.withPassword("extractor-secret-key-change-me");
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

		Path sqliteDbFile = Files.createTempFile("pos-doc-extractor-test", ".db");
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
	void extractsMultiplePdfsInOrderWithDeterministicIds() throws Exception {
		// Use a LinkedHashMap so the entry order is deterministic and
		// matches the assertion expectations on every JVM. Map.of(...)
		// does not specify iteration order across JVM versions.
		Map<String, byte[]> ordered = new java.util.LinkedHashMap<>();
		ordered.put("docs/first.pdf", PDF_A);
		ordered.put("docs/second.pdf", PDF_B);
		byte[] zipBytes = zipBytes(ordered);
		Path zip = Files.createTempFile("extract-test-", ".zip");
		Files.write(zip, zipBytes);

		UUID posRecordId = UUID.randomUUID();
		var extracted = this.service.extractAndStore(zip, zipBytes.length, posRecordId);

		assertEquals(2, extracted.size());
		assertEquals(0, extracted.get(0).sequence());
		assertEquals(1, extracted.get(1).sequence());
		assertEquals("first.pdf", extracted.get(0).filenameSegment());
		assertEquals("second.pdf", extracted.get(1).filenameSegment());

		UUID doc0Id = extracted.get(0).documentId();
		UUID doc1Id = extracted.get(1).documentId();
		assertNotEquals(doc0Id, doc1Id);
		assertEquals(doc0Id, DocumentIdentityDeriver.deriveDocumentId(posRecordId, 0));
		assertEquals(doc1Id, DocumentIdentityDeriver.deriveDocumentId(posRecordId, 1));

		// The stored bytes are byte-for-byte equal to the source entries.
		byte[] storedA = readMinioObject(extracted.get(0).objectKey());
		assertArrayEquals(PDF_A, storedA);
		byte[] storedB = readMinioObject(extracted.get(1).objectKey());
		assertArrayEquals(PDF_B, storedB);

		// Object keys contain only generated UUIDs and .pdf.
		assertTrue(extracted.get(0).objectKey().matches("documents/[0-9a-f-]{36}/[0-9a-f-]{36}\\.pdf"));
		assertTrue(extracted.get(1).objectKey().matches("documents/[0-9a-f-]{36}/[0-9a-f-]{36}\\.pdf"));
	}

	@Test
	void pdfWithoutMagicIsRejected() throws Exception {
		byte[] zipBytes = zipBytes(Map.of("notes/first.pdf", "not a pdf body".getBytes(StandardCharsets.UTF_8)));
		Path zip = Files.createTempFile("extract-bad-", ".zip");
		Files.write(zip, zipBytes);

		ConsumerException e = assertThrows(ConsumerException.class,
				() -> this.service.extractAndStore(zip, zipBytes.length, UUID.randomUUID()));
		assertEquals(ConsumerException.Code.SOURCE_ARCHIVE_INVALID, e.getCode());
	}

	@Test
	void pdfEntryExceedingPerEntryLimitIsRejected() throws Exception {
		UploadLimitsProperties tight = new UploadLimitsProperties(10485760L, 262144000L, 64L, 100, 100);
		ArchiveExtractionService tightService =
				new ArchiveExtractionService(new ZipArchiveValidator(tight), this.storage, tight);
		byte[] bigPdf = new byte[256];
		java.util.Arrays.fill(bigPdf, (byte) 'X');
		byte[] zipBytes = zipBytes(Map.of("big.pdf", bigPdf));
		Path zip = Files.createTempFile("extract-tight-", ".zip");
		Files.write(zip, zipBytes);

		ConsumerException e = assertThrows(ConsumerException.class,
				() -> tightService.extractAndStore(zip, zipBytes.length, UUID.randomUUID()));
		assertEquals(ConsumerException.Code.SOURCE_ARCHIVE_INVALID, e.getCode());
	}

	private byte[] readMinioObject(String objectKey) throws Exception {
		try (var stream = this.storage.get(objectKey);
				var out = new ByteArrayOutputStream()) {
			stream.transferTo(out);
			return out.toByteArray();
		}
	}

	private static byte[] zipBytes(Map<String, byte[]> entries) throws Exception {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(baos)) {
			for (Map.Entry<String, byte[]> e : entries.entrySet()) {
				ZipEntry entry = new ZipEntry(e.getKey());
				zip.putNextEntry(entry);
				zip.write(e.getValue());
				zip.closeEntry();
			}
		}
		return baos.toByteArray();
	}

	@SuppressWarnings("unused")
	private static ByteArrayInputStream unusedMarker() {
		return null;
	}

}