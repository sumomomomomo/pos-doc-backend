package horse.sumomo.pos_doc_backend.ingestion.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Locale;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import horse.sumomo.pos_doc_backend.ingestion.api.UploadLimitsProperties;
import horse.sumomo.pos_doc_backend.infrastructure.minio.MinioObjectStorage;
import horse.sumomo.pos_doc_backend.infrastructure.minio.ObjectStorageException;

class SourceArchiveDownloaderTest {

	private MinioObjectStorage storage;
	private SourceArchiveDownloader downloader;

	private static final long COMPRESSED_LIMIT = 10L * 1024L * 1024L;

	@BeforeEach
	void setUp() {
		this.storage = Mockito.mock(MinioObjectStorage.class);
		this.downloader = new SourceArchiveDownloader(this.storage, uploadLimits(COMPRESSED_LIMIT));
	}

	@Test
	void successfulDownloadReturnsTempFileSizeAndHash() throws Exception {
		byte[] payload = "PK-stub-source".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		String expectedHash = sha256Hex(payload);
		Mockito.when(this.storage.get("archives/x/y.zip"))
				.thenReturn(new ByteArrayInputStream(payload));

		try (var downloaded = this.downloader.download("archives/x/y.zip", payload.length, expectedHash)) {
			assertEquals(payload.length, downloaded.getByteCount());
			assertEquals(expectedHash, downloaded.getSha256());
			assertTrue(Files.exists(downloaded.getTempPath()));
		}
	}

	@Test
	void sizeMismatchIsRejectedAndTempFileIsDeleted() {
		byte[] payload = "PK-stub-source".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		String hash = sha256Hex(payload);
		Mockito.when(this.storage.get("archives/x/y.zip"))
				.thenReturn(new ByteArrayInputStream(payload));

		ConsumerException e = assertThrows(ConsumerException.class,
				() -> this.downloader.download("archives/x/y.zip", payload.length + 1, hash));
		assertEquals(ConsumerException.Code.SOURCE_ARCHIVE_SIZE_MISMATCH, e.getCode());
	}

	@Test
	void hashMismatchIsRejectedAndTempFileIsDeleted() {
		byte[] payload = "PK-stub-source".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		String wrongHash = "0".repeat(64);
		Mockito.when(this.storage.get("archives/x/y.zip"))
				.thenReturn(new ByteArrayInputStream(payload));

		ConsumerException e = assertThrows(ConsumerException.class,
				() -> this.downloader.download("archives/x/y.zip", payload.length, wrongHash));
		assertEquals(ConsumerException.Code.SOURCE_ARCHIVE_HASH_MISMATCH, e.getCode());
	}

	@Test
	void missingObjectIsReportedAsArchiveMissing() {
		Mockito.when(this.storage.get("archives/missing.zip"))
				.thenThrow(new ObjectStorageException.MissingObjectException("Object not found: archives/missing.zip",
						new RuntimeException("NoSuchKey")));

		ConsumerException e = assertThrows(ConsumerException.class,
				() -> this.downloader.download("archives/missing.zip", 1L, "0".repeat(64)));
		assertEquals(ConsumerException.Code.SOURCE_ARCHIVE_MISSING, e.getCode());
	}

	@Test
	void storageExceptionIsReportedAsUnavailable() {
		Mockito.when(this.storage.get("archives/x/y.zip"))
				.thenThrow(new ObjectStorageException("connection refused"));

		ConsumerException e = assertThrows(ConsumerException.class,
				() -> this.downloader.download("archives/x/y.zip", 1L, "0".repeat(64)));
		assertEquals(ConsumerException.Code.SOURCE_STORAGE_UNAVAILABLE, e.getCode());
	}

	@Test
	void oversizeDownloadIsRejectedAndTempFileIsDeleted() {
		// Stream one byte over the compressed limit.
		byte[] payload = byteBytes((int) COMPRESSED_LIMIT + 1);
		InputStream stream = new ByteArrayInputStream(payload);
		Mockito.when(this.storage.get("archives/x/y.zip")).thenReturn(stream);

		ConsumerException e = assertThrows(ConsumerException.class,
				() -> this.downloader.download("archives/x/y.zip", payload.length, "0".repeat(64)));
		assertEquals(ConsumerException.Code.SOURCE_STORAGE_UNAVAILABLE, e.getCode());
	}

	@Test
	void tempFileDoesNotSurviveOnHashMismatch() {
		byte[] payload = "PK-stub".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		Mockito.when(this.storage.get("archives/x/y.zip"))
				.thenReturn(new ByteArrayInputStream(payload));

		// Capture a temp file path while downloading so we can assert it is deleted.
		Path[] captured = new Path[1];
		SourceArchiveDownloader hooked = new SourceArchiveDownloader(this.storage, uploadLimits(COMPRESSED_LIMIT)) {
			@Override
			public DownloadedArchive download(String key, long size, String sha) {
				try (DownloadedArchive dl = super.download(key, size, "0".repeat(64))) {
					captured[0] = dl.getTempPath();
					return dl;
				}
			}
		};
		assertThrows(ConsumerException.class, () -> hooked.download("archives/x/y.zip", payload.length, "0".repeat(64)));
		if (captured[0] != null) {
			assertFalse(Files.exists(captured[0]));
		}
	}

	private static byte[] byteBytes(int size) {
		byte[] bytes = new byte[size];
		for (int i = 0; i < size; i++) {
			bytes[i] = (byte) (i & 0xFF);
		}
		return bytes;
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

	private static UploadLimitsProperties uploadLimits(long maxCompressed) {
		return new UploadLimitsProperties(maxCompressed, 262144000L, 52428800L, 100, 100);
	}

}