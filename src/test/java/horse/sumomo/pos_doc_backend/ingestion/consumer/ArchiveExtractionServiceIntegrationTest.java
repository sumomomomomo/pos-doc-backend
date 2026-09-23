package horse.sumomo.pos_doc_backend.ingestion.consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
			DockerImageName.parse("cgr.dev/chainguard/minio:latest").asCompatibleSubstituteFor("minio/minio");

	private static final byte[] PDF_A = ("%PDF-1.4\n% Document A\n%%EOF\n").getBytes(StandardCharsets.UTF_8);
	private static final byte[] PDF_B = ("%PDF-1.4\n% Document B\n%%EOF\n").getBytes(StandardCharsets.UTF_8);
	private static final byte[] PDF_MAGIC = { '%', 'P', 'D', 'F', '-' };
	private static final byte[] SERIALIZED_PREFIX = { (byte) 0xAC, (byte) 0xED, 0x00, 0x05 };

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

	// ------------------------------------------------------------------
	// Java-serialized byte[] (wrapped) PDF entries
	// ------------------------------------------------------------------

	@Test
	void mixedRawAndWrappedPdfsProduceNormalizedObjects() throws Exception {
		byte[] rawPdf = ("%PDF-1.4\n% Raw doc\n%%EOF\n").getBytes(StandardCharsets.UTF_8);
		byte[] wrappedPdf1 = ("%PDF-1.4\n% Wrapped doc 1\n%%EOF\n").getBytes(StandardCharsets.UTF_8);
		byte[] wrappedPdf2 = ("%PDF-1.4\n% Wrapped doc 2\n%%EOF\n").getBytes(StandardCharsets.UTF_8);

		Map<String, byte[]> ordered = new java.util.LinkedHashMap<>();
		ordered.put("docs/raw.pdf", rawPdf);
		ordered.put("docs/wrapped1.pdf", serialize(wrappedPdf1));
		ordered.put("docs/wrapped2.pdf", serialize(wrappedPdf2));
		byte[] zipBytes = zipBytes(ordered);
		Path zip = Files.createTempFile("extract-mixed-", ".zip");
		Files.write(zip, zipBytes);

		UUID posRecordId = UUID.randomUUID();
		var extracted = this.service.extractAndStore(zip, zipBytes.length, posRecordId);
		assertEquals(3, extracted.size());

		// 4. Persisted byte size is the NORMALIZED payload length (no wrapper).
		assertEquals(rawPdf.length, extracted.get(0).byteSize());
		assertEquals(wrappedPdf1.length, extracted.get(1).byteSize());
		assertEquals(wrappedPdf2.length, extracted.get(2).byteSize());

		// 1, 2. Every stored object is the normalized PDF, byte-for-byte.
		byte[] stored0 = readMinioObject(extracted.get(0).objectKey());
		byte[] stored1 = readMinioObject(extracted.get(1).objectKey());
		byte[] stored2 = readMinioObject(extracted.get(2).objectKey());
		assertArrayEquals(rawPdf, stored0);
		assertArrayEquals(wrappedPdf1, stored1);
		assertArrayEquals(wrappedPdf2, stored2);

		// 2. Every object begins exactly with %PDF- ...
		assertTrue(startsWith(stored0, PDF_MAGIC), "raw object must begin with %PDF-");
		assertTrue(startsWith(stored1, PDF_MAGIC), "wrapped object must be normalized to %PDF-");
		assertTrue(startsWith(stored2, PDF_MAGIC), "wrapped object must be normalized to %PDF-");
		// 3. ... and no stored object carries the serialized envelope.
		assertFalse(startsWith(stored1, SERIALIZED_PREFIX), "stored object must not carry the Java envelope");
		assertFalse(startsWith(stored2, SERIALIZED_PREFIX), "stored object must not carry the Java envelope");

		// 5. SHA-256 is computed over the NORMALIZED PDF bytes.
		assertEquals(sha256Hex(rawPdf), extracted.get(0).sha256());
		assertEquals(sha256Hex(wrappedPdf1), extracted.get(1).sha256());
		assertEquals(sha256Hex(wrappedPdf2), extracted.get(2).sha256());
	}

	@Test
	void rawExpandedTotalsIncludingWrappersAreUsedForSecurityLimits() throws Exception {
		byte[] p1 = ("%PDF-1.4\n% one\n%%EOF\n").getBytes(StandardCharsets.UTF_8);
		byte[] p2 = ("%PDF-1.4\n% two\n%%EOF\n").getBytes(StandardCharsets.UTF_8);
		long normalizedTotal = (long) p1.length + p2.length;
		long rawTotal = (27 + p1.length) + (27 + p2.length);
		// A limit strictly between the normalized and raw totals: the raw-PDF
		// archive fits, but the wrapped archive (whose raw total includes the two
		// 27-byte wrappers) does not.
		long limit = (27 + p1.length) + p2.length;
		assertTrue(limit > normalizedTotal, "limit must be above the normalized total");
		assertTrue(limit < rawTotal, "limit must be below the raw (wrapped) total");
		UploadLimitsProperties tight = new UploadLimitsProperties(10485760L, limit, limit, 100, 100);
		ArchiveExtractionService svc = new ArchiveExtractionService(new ZipArchiveValidator(tight), this.storage, tight);

		// Control: the same content as raw PDFs fits (total == normalizedTotal < limit).
		Map<String, byte[]> rawEntries = new java.util.LinkedHashMap<>();
		rawEntries.put("a.pdf", p1);
		rawEntries.put("b.pdf", p2);
		byte[] rawZip = zipBytes(rawEntries);
		Path rawZipPath = Files.createTempFile("extract-ctrl-", ".zip");
		Files.write(rawZipPath, rawZip);
		assertEquals(2, svc.extractAndStore(rawZipPath, rawZip.length, UUID.randomUUID()).size());

		// The wrapped archive's raw total (including the wrappers) exceeds the
		// limit and must be rejected as an invalid source archive.
		Map<String, byte[]> wrappedEntries = new java.util.LinkedHashMap<>();
		wrappedEntries.put("a.pdf", serialize(p1));
		wrappedEntries.put("b.pdf", serialize(p2));
		byte[] wrappedZip = zipBytes(wrappedEntries);
		Path wrappedZipPath = Files.createTempFile("extract-lim-", ".zip");
		Files.write(wrappedZipPath, wrappedZip);
		ConsumerException ex = assertThrows(ConsumerException.class,
				() -> svc.extractAndStore(wrappedZipPath, wrappedZip.length, UUID.randomUUID()));
		assertEquals(ConsumerException.Code.SOURCE_ARCHIVE_INVALID, ex.getCode());
	}

	@Test
	void extractAndStoreIsIdempotentForWrappedEntries() throws Exception {
		byte[] pdf = ("%PDF-1.4\n% idempotent\n%%EOF\n").getBytes(StandardCharsets.UTF_8);
		Map<String, byte[]> ordered = new java.util.LinkedHashMap<>();
		ordered.put("docs/a.pdf", serialize(pdf));
		byte[] zipBytes = zipBytes(ordered);
		Path zip = Files.createTempFile("extract-idem-", ".zip");
		Files.write(zip, zipBytes);

		UUID posRecordId = UUID.randomUUID();
		var first = this.service.extractAndStore(zip, zipBytes.length, posRecordId);
		var second = this.service.extractAndStore(zip, zipBytes.length, posRecordId);
		assertEquals(1, first.size());
		assertEquals(1, second.size());

		// Deterministic ids and keys are stable across attempts.
		assertEquals(first.get(0).documentId(), second.get(0).documentId());
		assertEquals(first.get(0).objectKey(), second.get(0).objectKey());
		// The second attempt saw the key as pre-existing.
		assertTrue(second.get(0).wasPreExisting(), "second attempt must mark the key pre-existing");
		// The stored normalized bytes are identical.
		assertArrayEquals(pdf, readMinioObject(first.get(0).objectKey()));
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

	private static byte[] serialize(byte[] pdfBytes) {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		try (java.io.ObjectOutputStream oos = new java.io.ObjectOutputStream(bos)) {
			oos.writeObject(pdfBytes);
		}
		catch (java.io.IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
		return bos.toByteArray();
	}

	private static boolean startsWith(byte[] bytes, byte[] prefix) {
		if (bytes.length < prefix.length) {
			return false;
		}
		for (int i = 0; i < prefix.length; i++) {
			if (bytes[i] != prefix[i]) {
				return false;
			}
		}
		return true;
	}

	private static String sha256Hex(byte[] bytes) {
		try {
			java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
			digest.update(bytes);
			StringBuilder sb = new StringBuilder();
			for (byte b : digest.digest()) {
				sb.append(String.format("%02x", b & 0xFF));
			}
			return sb.toString();
		}
		catch (Exception e) {
			throw new AssertionError(e);
		}
	}

	@SuppressWarnings("unused")
	private static ByteArrayInputStream unusedMarker() {
		return null;
	}

}