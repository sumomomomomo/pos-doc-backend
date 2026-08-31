package horse.sumomo.pos_doc_backend.ingestion.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import horse.sumomo.pos_doc_backend.ingestion.api.UploadLimitsProperties;
import horse.sumomo.pos_doc_backend.ingestion.archive.ZipArchiveValidator;
import horse.sumomo.pos_doc_backend.ingestion.consumer.ArchiveExtractionService.ExtractedPdf;
import horse.sumomo.pos_doc_backend.infrastructure.minio.MinioObjectStorage;
import horse.sumomo.pos_doc_backend.infrastructure.minio.ObjectStorageException;

/**
 * Compensation tests for {@link ArchiveExtractionService}.
 *
 * <p>Covers Task 6 acceptance criteria:
 * <ul>
 * <li>second-PDF upload failure: only objects newly created by the
 * current attempt are compensated,</li>
 * <li>pre-existing deterministic objects are never deleted by
 * compensation.</li>
 * </ul>
 *
 * <p>These are pure unit tests with mocked storage so they run without
 * a real MinIO server.
 */
class ArchiveExtractionServiceCompensationTest {

	private static final byte[] PDF_A = ("%PDF-1.4\n% Doc A\n%%EOF\n").getBytes();
	private static final byte[] PDF_B = ("%PDF-1.4\n% Doc B\n%%EOF\n").getBytes();

	private MinioObjectStorage storage;
	private ArchiveExtractionService service;

	@BeforeEach
	void setUp() {
		this.storage = mock(MinioObjectStorage.class);
		UploadLimitsProperties limits = new UploadLimitsProperties(
				10L * 1024L * 1024L,   // maxCompressedBytes
				262144000L,             // maxUncompressedBytes
				52428800L,              // maxEntryBytes
				100,                    // maxFileEntries
				100);                   // maxCompressionRatio
		this.service = new ArchiveExtractionService(
				new ZipArchiveValidator(limits), this.storage, limits);
	}

	@Test
	void compensateDeletesOnlyNewlyCreatedObjects() {
		UUID posRecordId = UUID.randomUUID();
		UUID newDocId = UUID.randomUUID();
		UUID oldDocId = UUID.randomUUID();
		String newKey = "documents/" + posRecordId + "/" + newDocId + ".pdf";
		String oldKey = "documents/" + posRecordId + "/" + oldDocId + ".pdf";

		ExtractedPdf newlyCreated = new ExtractedPdf(newDocId,
				UUID.randomUUID(), newKey, "first.pdf",
				PDF_A.length, sha256Hex(PDF_A), 0, false, Instant.now());
		ExtractedPdf preExisting = new ExtractedPdf(oldDocId,
				UUID.randomUUID(), oldKey, "second.pdf",
				PDF_B.length, sha256Hex(PDF_B), 1, true, Instant.now());

		this.service.compensate(List.of(newlyCreated, preExisting));

		// Only the newly created key was deleted.
		verify(this.storage, times(1)).delete(newKey);
		verify(this.storage, never()).delete(oldKey);
	}

	@Test
	void preExistingObjectSurvivesCompensation() {
		UUID posRecordId = UUID.randomUUID();
		UUID oldDocId = UUID.randomUUID();
		String oldKey = "documents/" + posRecordId + "/" + oldDocId + ".pdf";

		ExtractedPdf preExisting = new ExtractedPdf(oldDocId,
				UUID.randomUUID(), oldKey, "doc.pdf",
				PDF_A.length, sha256Hex(PDF_A), 0, true, Instant.now());

		this.service.compensate(List.of(preExisting));

		// Pre-existing object must survive compensation intact.
		verify(this.storage, never()).delete(anyString());
	}

	@Test
	void compensateContinuesAfterSingleDeleteFailure() {
		UUID posRecordId = UUID.randomUUID();
		UUID firstDocId = UUID.randomUUID();
		UUID secondDocId = UUID.randomUUID();
		String firstKey = "documents/" + posRecordId + "/" + firstDocId + ".pdf";
		String secondKey = "documents/" + posRecordId + "/" + secondDocId + ".pdf";

		ExtractedPdf first = new ExtractedPdf(firstDocId, UUID.randomUUID(), firstKey,
				"first.pdf", PDF_A.length, sha256Hex(PDF_A), 0, false, Instant.now());
		ExtractedPdf second = new ExtractedPdf(secondDocId, UUID.randomUUID(), secondKey,
				"second.pdf", PDF_B.length, sha256Hex(PDF_B), 1, false, Instant.now());

		// Make the first delete throw; the second must still be attempted.
		doThrow(new ObjectStorageException("delete failed for " + firstKey))
				.when(this.storage).delete(firstKey);

		// Compensation is best-effort; this must not throw.
		this.service.compensate(List.of(first, second));

		verify(this.storage, times(1)).delete(firstKey);
		verify(this.storage, times(1)).delete(secondKey);
	}

	@Test
	void compensateIsSafeOnEmptyOrNull() {
		this.service.compensate(List.of());
		this.service.compensate(null);
		verify(this.storage, never()).delete(anyString());
	}

	@Test
	void secondPdfUploadFailureDeletesOnlyFirstNewlyCreatedObject() throws Exception {
		UUID posRecordId = UUID.randomUUID();
		Map<String, byte[]> entries = new LinkedHashMap<>();
		entries.put("first.pdf", PDF_A);
		entries.put("second.pdf", PDF_B);
		Path zip = writeZip(entries);

		UUID firstDocId = DocumentIdentityDeriver.deriveDocumentId(posRecordId, 0);
		UUID secondDocId = DocumentIdentityDeriver.deriveDocumentId(posRecordId, 1);
		String firstKey = DocumentIdentityDeriver.buildDocumentObjectKey(posRecordId, firstDocId);
		String secondKey = DocumentIdentityDeriver.buildDocumentObjectKey(posRecordId, secondDocId);

		// Both keys "pre-exist" -> service will NOT call put() for either.
		// Inject a failure on the existence probe for the second key
		// to simulate a storage outage that hits the second entry.
		when(this.storage.exists(firstKey)).thenReturn(true);
		when(this.storage.exists(secondKey)).thenThrow(
		 new ObjectStorageException("simulated storage outage on second probe"));

		ConsumerException thrown = assertThrows(ConsumerException.class,
				() -> this.service.extractAndStore(zip, Files.size(zip), posRecordId));
		assertEquals(ConsumerException.Code.SOURCE_STORAGE_UNAVAILABLE, thrown.getCode());

		// No put() because both keys were reported as pre-existing.
		verify(this.storage, never()).put(eq(firstKey), any(InputStream.class), anyLong(), anyString());
		verify(this.storage, never()).put(eq(secondKey), any(InputStream.class), anyLong(), anyString());

		// Compensation must not delete the pre-existing keys.
		verify(this.storage, never()).delete(anyString());

		// Second scenario: neither key pre-exists, the first upload
		// succeeds, the second upload fails. The service must compensate
		// only the first (newly created) key.
		Mockito.reset(this.storage);
		when(this.storage.exists(firstKey)).thenReturn(false);
		when(this.storage.exists(secondKey)).thenReturn(false);

		doThrow(new ObjectStorageException("simulated failure on second upload"))
				.when(this.storage).put(eq(secondKey), any(InputStream.class), anyLong(), anyString());

		ConsumerException second = assertThrows(ConsumerException.class,
				() -> this.service.extractAndStore(zip, Files.size(zip), posRecordId));
		assertNotNull(second);

		// First was uploaded, second failed.
		verify(this.storage, times(1)).put(eq(firstKey), any(InputStream.class), anyLong(), anyString());
		verify(this.storage, times(1)).put(eq(secondKey), any(InputStream.class), anyLong(), anyString());

		// Compensation must delete only the first key (newly created).
		verify(this.storage, times(1)).delete(firstKey);
		verify(this.storage, never()).delete(secondKey);
	}

	private static Path writeZip(Map<String, byte[]> entries) throws Exception {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(baos)) {
			for (var e : entries.entrySet()) {
				ZipEntry entry = new ZipEntry(e.getKey());
				zip.putNextEntry(entry);
				zip.write(e.getValue());
				zip.closeEntry();
			}
		}
		Path tmp = Files.createTempFile("pos-doc-compensation-test-", ".zip");
		Files.write(tmp, baos.toByteArray());
		tmp.toFile().deleteOnExit();
		return tmp;
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

	// Sanity helpers for the JUnit assertion surface used in this file.
	@SuppressWarnings("unused")
	private static void typeAssertionsUsed() {
		assertTrue(true);
		assertFalse(false);
		assertEquals(0, 0);
	}
}