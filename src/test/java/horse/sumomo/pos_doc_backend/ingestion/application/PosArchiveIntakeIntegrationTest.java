package horse.sumomo.pos_doc_backend.ingestion.application;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import io.minio.MinioClient;
import io.minio.MakeBucketArgs;
import io.minio.ListObjectsArgs;
import io.minio.StatObjectResponse;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.utility.DockerImageName;

import horse.sumomo.pos_doc_backend.infrastructure.minio.MinioObjectStorage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real MinIO + SQLite integration tests for the full intake service (Task
 * 4-5, step 21).
 *
 * <p>Uses a real Testcontainers MinIO instance and a real temporary SQLite
 * database. The outbox relay is disabled: no RabbitMQ is started here.
 * MinIO is never mocked on the happy path.
 */
@SpringBootTest(properties = { "app.messaging.outbox.enabled=false" })
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PosArchiveIntakeIntegrationTest {

	private static final String TEST_BUCKET = "pos-documents-intake-test";
	private static final DockerImageName MINIO_IMAGE =
			DockerImageName.parse("minio/minio:RELEASE.2025-09-07T16-13-09Z");
	private static final String FIXTURE_SHA256 = "1ce96e72137fd1b084410d8f1f9154bce9dfd435fc9e9ab6a8ea340968e362a0";

	private static final byte[] PDF = "%PDF-1.4\n% dummy test document\n%%EOF\n".getBytes(StandardCharsets.UTF_8);

	private static MinIOContainer minio;
	private static MinioClient adminClient;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private MinioObjectStorage storage;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@DynamicPropertySource
	static void containerProperties(DynamicPropertyRegistry registry) throws Exception {
		minio = new MinIOContainer(MINIO_IMAGE)
				.withUserName("intake-access-key")
				.withPassword("intake-secret-key-change-me");
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

		Path sqliteDbFile = Files.createTempFile("pos-doc-intake-test", ".db");
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

	// ------------------------------------------------------------------
	// happy path
	// ------------------------------------------------------------------

	@Test
	void validZipIsStoredByteForByteAndAllRowsArePersisted() throws Exception {
		byte[] zipBytes = zipBytes(Map.of("documents/first.pdf", PDF, "documents/second.pdf", PDF));
		MockMultipartFile file = new MockMultipartFile("file", "EREF-2026-101.zip", "application/zip", zipBytes);

		MvcResult result = this.mockMvc.perform(multipart("/pos-records").file(file))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("UPLOADED"))
				.andExpect(jsonPath("$.posRecordId").isNotEmpty())
				.andExpect(jsonPath("$.jobId").isNotEmpty())
				.andExpect(header().exists("Location"))
				.andReturn();

		String posRecordId = readJsonField(result, "posRecordId");
		String storageObjectId = this.jdbcTemplate.queryForObject(
				"SELECT source_archive_id FROM pos_record WHERE id = ?", String.class, posRecordId);
		String objectKey = this.jdbcTemplate.queryForObject(
				"SELECT object_key FROM storage_object WHERE id = ?", String.class, storageObjectId);

		// The object key uses only generated UUIDs and .zip.
		assertTrue(objectKey.matches("archives/[0-9a-f-]{36}/[0-9a-f-]{36}\\.zip"),
				"object key must be archives/{posRecordId}/{storageObjectId}.zip");
		assertFalse(objectKey.contains("EREF"), "object key must not contain the eRef");
		assertEquals("archives/" + posRecordId + "/" + storageObjectId + ".zip", objectKey);

		// MinIO holds the exact bytes, with the right content type and size.
		byte[] stored = readAll(storage.get(objectKey));
		assertArrayEquals(zipBytes, stored, "MinIO must store the archive byte-for-byte");
		StatObjectResponse stat = this.storage.stat(objectKey);
		assertEquals("application/zip", stat.contentType());
		assertEquals(zipBytes.length, stat.size());

		// All four rows exist; no pos_document rows yet.
		assertEquals(1, this.jdbcTemplate.queryForObject(
				"SELECT count(*) FROM storage_object WHERE id = ?", Integer.class, storageObjectId));
		assertEquals("UPLOADED", this.jdbcTemplate.queryForObject(
				"SELECT status FROM pos_record WHERE id = ?", String.class, posRecordId));
		assertEquals("QUEUED", this.jdbcTemplate.queryForObject(
				"SELECT status FROM ingestion_job WHERE pos_record_id = ?", String.class, posRecordId));
		assertEquals(0, this.jdbcTemplate.queryForObject(
				"SELECT attempt_count FROM ingestion_job WHERE pos_record_id = ?", Integer.class, posRecordId));
		assertEquals(1, this.jdbcTemplate.queryForObject(
				"SELECT count(*) FROM outbox_event WHERE aggregate_id = (SELECT id FROM ingestion_job "
						+ "WHERE pos_record_id = ?)", Integer.class, posRecordId));
		assertEquals(0, this.jdbcTemplate.queryForObject(
				"SELECT count(*) FROM pos_document WHERE pos_record_id = ?", Integer.class, posRecordId),
				"pos_document rows must not be created in this task");

		// The filename is metadata only: stored on storage_object, but never
		// in the object key or the outbox JSON.
		String storedFilename = this.jdbcTemplate.queryForObject(
				"SELECT original_filename FROM storage_object WHERE id = ?", String.class, storageObjectId);
		assertEquals("EREF-2026-101.zip", storedFilename);

		String outboxJson = this.jdbcTemplate.queryForObject(
				"SELECT payload_json FROM outbox_event WHERE aggregate_id = (SELECT id FROM ingestion_job "
						+ "WHERE pos_record_id = ?)", String.class, posRecordId);
		assertFalse(outboxJson.contains("EREF"), "outbox JSON must not contain the eRef");
		assertFalse(outboxJson.contains(".zip"), "outbox JSON must not contain the filename");
		assertFalse(outboxJson.contains(objectKey), "outbox JSON must not contain the object key");
	}

	@Test
	void committedFixtureHasTheDocumentedSha256() throws Exception {
		Path fixture = Path.of("src/test/resources/fixtures/valid-two-pdf.zip");
		byte[] bytes = Files.readAllBytes(fixture);
		assertEquals(FIXTURE_SHA256, sha256Hex(bytes), "the committed fixture was replaced or corrupted");
		assertEquals(2, pdfEntryCount(bytes), "the fixture must contain exactly two PDF entries");
	}

	// ------------------------------------------------------------------
	// invalid inputs
	// ------------------------------------------------------------------

	@Test
	void invalidZipMakesNoMinIOObjectAndNoDatabaseRows() throws Exception {
		int objectsBefore = countMinioObjects();
		int recordsBefore = this.jdbcTemplate.queryForObject("SELECT count(*) FROM pos_record", Integer.class);

		// A .txt entry instead of PDFs.
		byte[] badZip = zipBytes(Map.of("documents/notes.txt", "not a pdf".getBytes(StandardCharsets.UTF_8)));
		this.mockMvc.perform(multipart("/pos-records")
				.file(new MockMultipartFile("file", "EREF-2026-201.zip", "application/zip", badZip)))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("INVALID_ARCHIVE"));

		// A traversal entry.
		byte[] traversalZip = zipBytes(Map.of("../escape.pdf", PDF));
		this.mockMvc.perform(multipart("/pos-records")
				.file(new MockMultipartFile("file", "EREF-2026-202.zip", "application/zip", traversalZip)))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("INVALID_ARCHIVE"));

		assertEquals(objectsBefore, countMinioObjects(), "invalid uploads must not create MinIO objects");
		assertEquals(recordsBefore, this.jdbcTemplate.queryForObject(
				"SELECT count(*) FROM pos_record", Integer.class), "invalid uploads must not create rows");
	}

	@Test
	void duplicateErefAfterUploadCompensatesOnlyTheNewObject() throws Exception {
		byte[] zipBytes = zipBytes(Map.of("documents/first.pdf", PDF, "documents/second.pdf", PDF));
		int recordsBefore = this.jdbcTemplate.queryForObject("SELECT count(*) FROM pos_record", Integer.class);
		int jobsBefore = this.jdbcTemplate.queryForObject("SELECT count(*) FROM ingestion_job", Integer.class);
		int outboxBefore = this.jdbcTemplate.queryForObject("SELECT count(*) FROM outbox_event", Integer.class);
		java.util.Set<String> keysBefore = objectKeySet();

		// First upload succeeds.
		this.mockMvc.perform(multipart("/pos-records")
				.file(new MockMultipartFile("file", "EREF-2026-301.zip", "application/zip", zipBytes)))
				.andExpect(status().isAccepted());

		// The first upload created exactly one new object.
		java.util.Set<String> keysAfterFirst = objectKeySet();
		keysAfterFirst.removeAll(keysBefore);
		assertEquals(1, keysAfterFirst.size(), "first upload must create exactly one new object");
		String firstKey = keysAfterFirst.iterator().next();
		byte[] firstBytes = readAll(storage.get(firstKey));

		// Duplicate eRef (same normalized eRef) fails at the database step.
		this.mockMvc.perform(multipart("/pos-records")
				.file(new MockMultipartFile("file", "EREF-2026-301.zip", "application/zip", zipBytes)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("DUPLICATE_EREF_NUMBER"));

		// Compensation deleted only the newly uploaded object: the bucket is
		// back to the post-first-upload state (pre-existing objects from other
		// tests are untouched), and the original object is byte-identical.
		java.util.Set<String> expectedAfter = new java.util.HashSet<>(keysBefore);
		expectedAfter.add(firstKey);
		assertEquals(expectedAfter, objectKeySet(), "compensation must leave exactly the original object");
		byte[] stored = readAll(storage.get(firstKey));
		assertArrayEquals(firstBytes, stored, "the original object must remain byte-identical");

		// No duplicate record/job/outbox rows were committed.
		assertEquals(recordsBefore + 1, this.jdbcTemplate.queryForObject(
				"SELECT count(*) FROM pos_record", Integer.class));
		assertEquals(jobsBefore + 1, this.jdbcTemplate.queryForObject(
				"SELECT count(*) FROM ingestion_job", Integer.class));
		assertEquals(outboxBefore + 1, this.jdbcTemplate.queryForObject(
				"SELECT count(*) FROM outbox_event", Integer.class));
	}

	@Test
	void blankPolicyNumberIsRejectedBeforeAnySideEffects() throws Exception {
		int objectsBefore = countMinioObjects();
		byte[] zipBytes = zipBytes(Map.of("documents/first.pdf", PDF));

		this.mockMvc.perform(multipart("/pos-records")
				.file(new MockMultipartFile("file", "EREF-2026-401.zip", "application/zip", zipBytes))
				.param("policyNumber", "   "))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_POLICY_NUMBER"));

		assertEquals(objectsBefore, countMinioObjects());
	}

	@Test
	void disallowedContentTypeIsRejectedEvenForValidZipBytes() throws Exception {
		byte[] zipBytes = zipBytes(Map.of("documents/first.pdf", PDF));
		this.mockMvc.perform(multipart("/pos-records")
				.file(new MockMultipartFile("file", "EREF-2026-402.zip", "application/pdf", zipBytes)))
				.andExpect(status().isUnsupportedMediaType())
				.andExpect(jsonPath("$.code").value("UNSUPPORTED_ARCHIVE_TYPE"));
	}

	@Test
	void missingFilePartIsRejectedWithMissingFile() throws Exception {
		this.mockMvc.perform(multipart("/pos-records"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("MISSING_FILE"));
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	private static byte[] zipBytes(Map<String, byte[]> entries) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(out)) {
			for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
				ZipEntry zipEntry = new ZipEntry(entry.getKey());
				zipEntry.setMethod(ZipEntry.DEFLATED);
				zip.putNextEntry(zipEntry);
				zip.write(entry.getValue());
				zip.closeEntry();
			}
		}
		return out.toByteArray();
	}

	private static int pdfEntryCount(byte[] zipBytes) throws Exception {
		Path temp = Files.createTempFile("zip-intent-", ".zip");
		temp.toFile().deleteOnExit();
		Files.write(temp, zipBytes);
		try (ZipFile zip = new ZipFile(temp.toFile())) {
			var entries = zip.entries();
			int count = 0;
			while (entries.hasMoreElements()) {
				if (!entries.nextElement().isDirectory()) {
					count++;
				}
			}
			return count;
		}
	}

	private static String sha256Hex(byte[] bytes) throws Exception {
		byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
		StringBuilder sb = new StringBuilder();
		for (byte b : digest) {
			sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
		}
		return sb.toString();
	}

	private static String readJsonField(MvcResult result, String field) throws Exception {
		String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
		int idx = body.indexOf("\"" + field + "\":\"");
		if (idx < 0) {
			throw new IllegalStateException("field not found in response: " + field);
		}
		// idx is at the opening quote of the field name; "field":" is
		// field.length() + 4 characters (name, closing quote, colon, value
		// opening quote).
		int start = idx + field.length() + 4;
		int end = body.indexOf('"', start);
		return body.substring(start, end);
	}

	private int countMinioObjects() throws Exception {
		int count = 0;
		for (var result : adminClient.listObjects(ListObjectsArgs.builder()
				.bucket(TEST_BUCKET)
				.recursive(true)
				.build())) {
			result.get();
			count++;
		}
		return count;
	}

	private java.util.Set<String> objectKeySet() throws Exception {
		var keys = new java.util.HashSet<String>();
		for (var result : adminClient.listObjects(ListObjectsArgs.builder()
				.bucket(TEST_BUCKET)
				.recursive(true)
				.build())) {
			keys.add(result.get().objectName());
		}
		return keys;
	}

	private static byte[] readAll(InputStream in) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		int n;
		while ((n = in.read(buffer)) != -1) {
			out.write(buffer, 0, n);
		}
		in.close();
		return out.toByteArray();
	}

}
