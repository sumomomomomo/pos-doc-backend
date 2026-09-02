package horse.sumomo.pos_doc_backend.rendering.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import horse.sumomo.pos_doc_backend.infrastructure.minio.MinioObjectStorage;
import horse.sumomo.pos_doc_backend.infrastructure.minio.ObjectStorageException;
import horse.sumomo.pos_doc_backend.rendering.api.FirstPageRenderingProperties;
import horse.sumomo.pos_doc_backend.rendering.model.DocumentRenderSource;

/**
 * Unit tests for {@link StoredPdfMaterializer} using a controllable fake
 * storage stream.
 */
class StoredPdfMaterializerTest {

	private static final long FIFTY_MIB = 52428800L;

	private MinioObjectStorage storage;
	private StoredPdfMaterializer materializer;

	@BeforeEach
	void setUp() {
		this.storage = Mockito.mock(MinioObjectStorage.class);
		FirstPageRenderingProperties props = new FirstPageRenderingProperties(200, FIFTY_MIB, 5000, 5000,
				16000000L, 33554432L, 1);
		this.materializer = new StoredPdfMaterializer(this.storage, props);
	}

	@Test
	void successfulDownloadReturnsTempFileWithExactSizeAndHash() throws Exception {
		byte[] payload = "%PDF-1.4\n% test\n%%EOF\n".getBytes(StandardCharsets.UTF_8);
		String hash = sha256Hex(payload);
		Mockito.when(this.storage.get("documents/x/y.pdf"))
				.thenReturn(new ByteArrayInputStream(payload));

		DocumentRenderSource source = source("documents/x/y.pdf", payload.length, hash);
		try (StoredPdfMaterializer.MaterializedPdf pdf = this.materializer.materialize(source)) {
			assertEquals(payload.length, pdf.getByteCount());
			assertTrue(Files.exists(pdf.getTempPath()));
		}
		// After close, the temp file is deleted.
	}

	@Test
	void emptyStreamFailsWithSizeMismatch() {
		byte[] payload = new byte[0];
		String hash = sha256Hex(payload);
		Mockito.when(this.storage.get("documents/x/y.pdf"))
				.thenReturn(new ByteArrayInputStream(payload));

		DocumentRenderSource source = source("documents/x/y.pdf", 100L, hash);
		RenderingException e = assertThrows(RenderingException.class,
				() -> this.materializer.materialize(source));
		assertEquals(RenderingException.Code.PDF_SIZE_MISMATCH, e.getCode());
	}

	@Test
	void shorterStreamFailsWithSizeMismatch() {
		byte[] payload = "short".getBytes(StandardCharsets.UTF_8);
		String hash = sha256Hex(payload);
		Mockito.when(this.storage.get("documents/x/y.pdf"))
				.thenReturn(new ByteArrayInputStream(payload));

		DocumentRenderSource source = source("documents/x/y.pdf", payload.length + 10, hash);
		RenderingException e = assertThrows(RenderingException.class,
				() -> this.materializer.materialize(source));
		assertEquals(RenderingException.Code.PDF_SIZE_MISMATCH, e.getCode());
	}

	@Test
	void longerStreamFailsWithSizeMismatch() {
		byte[] payload = "longer payload".getBytes(StandardCharsets.UTF_8);
		String hash = sha256Hex(payload);
		Mockito.when(this.storage.get("documents/x/y.pdf"))
				.thenReturn(new ByteArrayInputStream(payload));

		DocumentRenderSource source = source("documents/x/y.pdf", payload.length - 5, hash);
		RenderingException e = assertThrows(RenderingException.class,
				() -> this.materializer.materialize(source));
		assertEquals(RenderingException.Code.PDF_SIZE_MISMATCH, e.getCode());
	}

	@Test
	void overConfiguredLimitFailsWithSizeMismatch() {
		// Use a small configured limit to avoid allocating 50 MiB.
		FirstPageRenderingProperties smallProps = new FirstPageRenderingProperties(200, 100L, 5000, 5000,
				16000000L, 33554432L, 1);
		StoredPdfMaterializer smallMaterializer = new StoredPdfMaterializer(this.storage, smallProps);

		byte[] payload = new byte[101];
		String hash = sha256Hex(payload);
		Mockito.when(this.storage.get("documents/x/y.pdf"))
				.thenReturn(new ByteArrayInputStream(payload));

		DocumentRenderSource source = source("documents/x/y.pdf", payload.length, hash);
		RenderingException e = assertThrows(RenderingException.class,
				() -> smallMaterializer.materialize(source));
		assertEquals(RenderingException.Code.PDF_SIZE_MISMATCH, e.getCode());
	}

	@Test
	void hashMismatchFailsWithHashMismatch() {
		byte[] payload = "%PDF-1.4\n% test\n%%EOF\n".getBytes(StandardCharsets.UTF_8);
		String wrongHash = "0".repeat(64);
		Mockito.when(this.storage.get("documents/x/y.pdf"))
				.thenReturn(new ByteArrayInputStream(payload));

		DocumentRenderSource source = source("documents/x/y.pdf", payload.length, wrongHash);
		RenderingException e = assertThrows(RenderingException.class,
				() -> this.materializer.materialize(source));
		assertEquals(RenderingException.Code.PDF_HASH_MISMATCH, e.getCode());
	}

	@Test
	void streamThatThrowsMidwayBecomesPdfStorageUnavailable() {
		byte[] payload = "%PDF-1.4\n% test\n%%EOF\n".getBytes(StandardCharsets.UTF_8);
		String hash = sha256Hex(payload);
		InputStream throwingStream = new InputStream() {
			private int remaining = 3;

			@Override
			public int read() {
				if (this.remaining-- > 0) {
					return 'P';
				}
				throw new RuntimeException("simulated network failure");
			}
		};
		Mockito.when(this.storage.get("documents/x/y.pdf")).thenReturn(throwingStream);

		DocumentRenderSource source = source("documents/x/y.pdf", payload.length, hash);
		RenderingException e = assertThrows(RenderingException.class,
				() -> this.materializer.materialize(source));
		assertEquals(RenderingException.Code.PDF_STORAGE_UNAVAILABLE, e.getCode());
	}

	@Test
	void missingObjectBecomesPdfObjectMissing() {
		Mockito.when(this.storage.get("documents/missing.pdf"))
				.thenThrow(new ObjectStorageException.MissingObjectException("Object not found",
						new RuntimeException("NoSuchKey")));

		DocumentRenderSource source = source("documents/missing.pdf", 100L, "0".repeat(64));
		RenderingException e = assertThrows(RenderingException.class,
				() -> this.materializer.materialize(source));
		assertEquals(RenderingException.Code.PDF_OBJECT_MISSING, e.getCode());
	}

	@Test
	void storageExceptionBecomesPdfStorageUnavailable() {
		Mockito.when(this.storage.get("documents/x/y.pdf"))
				.thenThrow(new ObjectStorageException("connection refused"));

		DocumentRenderSource source = source("documents/x/y.pdf", 100L, "0".repeat(64));
		RenderingException e = assertThrows(RenderingException.class,
				() -> this.materializer.materialize(source));
		assertEquals(RenderingException.Code.PDF_STORAGE_UNAVAILABLE, e.getCode());
	}

	@Test
	void tempFileIsDeletedOnSizeMismatch() throws Exception {
		byte[] payload = "short".getBytes(StandardCharsets.UTF_8);
		String hash = sha256Hex(payload);
		Mockito.when(this.storage.get("documents/x/y.pdf"))
				.thenReturn(new ByteArrayInputStream(payload));

		// Capture the temp file path at creation time by overriding
		// createTempFile().
		Path[] captured = new Path[1];
		StoredPdfMaterializer hooked = new StoredPdfMaterializer(this.storage,
				new FirstPageRenderingProperties(200, FIFTY_MIB, 5000, 5000, 16000000L, 33554432L, 1)) {
			@Override
			Path createTempFile() throws java.io.IOException {
				Path p = super.createTempFile();
				captured[0] = p;
				return p;
			}
		};

		DocumentRenderSource source = source("documents/x/y.pdf", payload.length + 10, hash);
		assertThrows(RenderingException.class, () -> hooked.materialize(source));
		// The temp file path was captured at creation time; assert it is gone.
		assertNotNull(captured[0], "temp file path must be captured at creation");
		assertFalse(Files.exists(captured[0]),
				"temp PDF file must be deleted after size mismatch failure");
	}

	@Test
	void tempFileIsDeletedOnHashMismatch() throws Exception {
		byte[] payload = "%PDF-1.4\n% test\n%%EOF\n".getBytes(StandardCharsets.UTF_8);
		String wrongHash = "0".repeat(64);
		Mockito.when(this.storage.get("documents/x/y.pdf"))
				.thenReturn(new ByteArrayInputStream(payload));

		Path[] captured = new Path[1];
		StoredPdfMaterializer hooked = new StoredPdfMaterializer(this.storage,
				new FirstPageRenderingProperties(200, FIFTY_MIB, 5000, 5000, 16000000L, 33554432L, 1)) {
			@Override
			Path createTempFile() throws java.io.IOException {
				Path p = super.createTempFile();
				captured[0] = p;
				return p;
			}
		};

		DocumentRenderSource source = source("documents/x/y.pdf", payload.length, wrongHash);
		assertThrows(RenderingException.class, () -> hooked.materialize(source));
		assertNotNull(captured[0], "temp file path must be captured at creation");
		assertFalse(Files.exists(captured[0]),
				"temp PDF file must be deleted after hash mismatch failure");
	}

	@Test
	void closingHandleTwiceIsSafe() throws Exception {
		byte[] payload = "%PDF-1.4\n% test\n%%EOF\n".getBytes(StandardCharsets.UTF_8);
		String hash = sha256Hex(payload);
		Mockito.when(this.storage.get("documents/x/y.pdf"))
				.thenReturn(new ByteArrayInputStream(payload));

		DocumentRenderSource source = source("documents/x/y.pdf", payload.length, hash);
		StoredPdfMaterializer.MaterializedPdf pdf = this.materializer.materialize(source);
		Path tempPath = pdf.getTempPath();
		assertTrue(Files.exists(tempPath));
		pdf.close();
		assertFalse(Files.exists(tempPath));
		pdf.close(); // Second close is a no-op.
	}

	@Test
	void toStringContainsNoPii() throws Exception {
		byte[] payload = "%PDF-1.4\n% test\n%%EOF\n".getBytes(StandardCharsets.UTF_8);
		String hash = sha256Hex(payload);
		Mockito.when(this.storage.get("documents/x/y.pdf"))
				.thenReturn(new ByteArrayInputStream(payload));

		DocumentRenderSource source = source("documents/x/y.pdf", payload.length, hash);
		try (StoredPdfMaterializer.MaterializedPdf pdf = this.materializer.materialize(source)) {
			String str = pdf.toString();
			assertFalse(str.contains("documents/x/y.pdf"), "toString must not contain the object key");
			assertFalse(str.contains(pdf.getTempPath().toString()), "toString must not contain the temp path");
			assertFalse(str.contains(hash), "toString must not contain the hash");
		}
	}

	@Test
	void exceptionMessageContainsNoPii() {
		byte[] payload = "short".getBytes(StandardCharsets.UTF_8);
		String hash = sha256Hex(payload);
		Mockito.when(this.storage.get("documents/x/y.pdf"))
				.thenReturn(new ByteArrayInputStream(payload));

		DocumentRenderSource source = source("documents/x/y.pdf", payload.length + 10, hash);
		RenderingException e = assertThrows(RenderingException.class,
				() -> this.materializer.materialize(source));
		String msg = e.getMessage();
		assertFalse(msg.contains("documents/x/y.pdf"), "exception message must not contain the object key");
		assertFalse(msg.contains(hash), "exception message must not contain the hash");
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	private static DocumentRenderSource source(String objectKey, long expectedSize, String expectedSha) {
		return new DocumentRenderSource(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				objectKey, expectedSize, expectedSha);
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

}
