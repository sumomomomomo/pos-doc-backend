package horse.sumomo.pos_doc_backend.ingestion.archive;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import horse.sumomo.pos_doc_backend.ingestion.api.UploadLimitsProperties;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link BoundedUploadSpooler}.
 *
 * <p>The spooler does not close the source stream (the owner does); these
 * tests use plain {@link ByteArrayInputStream}s, which are unaffected by
 * close.
 */
class BoundedUploadSpoolerTest {

	private static final UploadLimitsProperties LIMITS = new UploadLimitsProperties(10485760L, 262144000L,
			52428800L, 100, 100);

	private final BoundedUploadSpooler spooler = new BoundedUploadSpooler(LIMITS);

	private final java.util.List<PathHolder> spooled = new java.util.ArrayList<>();

	@AfterEach
	void cleanUp() {
		for (PathHolder h : this.spooled) {
			h.close();
		}
		this.spooled.clear();
	}

	private SpooledUpload spoolAndTrack(byte[] content) {
		SpooledUpload upload = this.spooler.spool(new ByteArrayInputStream(content));
		this.spooled.add(new PathHolder(upload));
		return upload;
	}

	@Test
	void exactBytesAndSha256ArePreserved() throws Exception {
		byte[] content = "dummy-archive-bytes-0123456789".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		String expectedSha = sha256Hex(content);

		SpooledUpload upload = spoolAndTrack(content);
		assertEquals(content.length, upload.getByteCount());
		assertEquals(expectedSha, upload.getSha256());
		assertEquals(64, upload.getSha256().length());

		byte[] onDisk = Files.readAllBytes(upload.getTempPath());
		assertArrayEquals(content, onDisk, "spooled file must contain exactly the source bytes");
	}

	@Test
	void emptyUploadIsRejected() {
		ArchiveValidationException e = assertThrows(ArchiveValidationException.class,
				() -> this.spooler.spool(new ByteArrayInputStream(new byte[0])));
		assertEquals(ArchiveValidationException.Category.EMPTY_UPLOAD, e.getCategory());
	}

	@Test
	void exactlyTenMibIsAccepted() {
		byte[] content = new byte[10485760];
		Arrays.fill(content, (byte) 'x');

		SpooledUpload upload = spoolAndTrack(content);
		assertEquals(10485760L, upload.getByteCount());
		assertNotNull(upload.getSha256());
		assertTrue(Files.exists(upload.getTempPath()));
	}

	@Test
	void tenMibPlusOneByteIsRejected() {
		// A stream that yields 10 MiB + 1 byte without materializing a
		// larger array than necessary.
		ArchiveValidationException e = assertThrows(ArchiveValidationException.class,
				() -> this.spooler.spool(new BigInputStream(10485761)));
		assertEquals(ArchiveValidationException.Category.ARCHIVE_TOO_LARGE, e.getCategory());
	}

	@Test
	void partialTempFileIsRemovedAfterSizeFailure() {
		Set<Path> before = spoolTempFiles();
		try {
			this.spooler.spool(new BigInputStream(10485761));
			throw new AssertionError("expected size failure");
		}
		catch (ArchiveValidationException expected) {
			// nothing
		}
		Set<Path> after = spoolTempFiles();
		after.removeAll(before);
		assertTrue(after.isEmpty(),
				"a failed spool must not leave a partial temp file behind: " + after);
	}

	@Test
	void closeRemovesOnlyOwnedTempFileAndIsIdempotent() throws Exception {
		SpooledUpload upload = this.spooler.spool(new ByteArrayInputStream("abc".getBytes()));
		this.spooled.remove(new PathHolder(upload));

		Path owned = upload.getTempPath();
		Path sibling = Files.createTempFile("sibling-not-owned-", ".tmp");
		sibling.toFile().deleteOnExit();
		try {
			assertTrue(Files.exists(owned));

			upload.close();
			assertFalse(Files.exists(owned), "close() must delete the owned temp file");

			// Idempotent: a second close is a no-op.
			upload.close();

			assertTrue(Files.exists(sibling), "close() must not delete other files");
		}
		finally {
			Files.deleteIfExists(sibling);
			upload.close();
		}
	}

	@Test
	void spooledToStringExposesNoPathOrHash() {
		SpooledUpload upload = this.spooler.spool(new ByteArrayInputStream("abc".getBytes()));
		this.spooled.add(new PathHolder(upload));
		String rendered = upload.toString();
		assertFalse(rendered.contains(upload.getTempPath().toString()),
				"toString() must not expose the temp path");
		assertFalse(rendered.contains(upload.getSha256()), "toString() must not expose the hash");
	}

	private static Set<Path> spoolTempFiles() {
		Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"));
		Set<Path> found = new java.util.HashSet<>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(tempDir, "pos-doc-upload-*.part")) {
			for (Path path : stream) {
				found.add(path);
			}
		}
		catch (java.io.IOException e) {
			throw new IllegalStateException(e);
		}
		return found;
	}

	private static String sha256Hex(byte[] bytes) throws Exception {
		byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
		StringBuilder sb = new StringBuilder();
		for (byte b : digest) {
			sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
		}
		return sb.toString();
	}

	/**
	 * Tracks a SpooledUpload for cleanup without closing it prematurely.
	 */
	private record PathHolder(SpooledUpload upload) implements AutoCloseable {
		@Override
		public void close() {
			this.upload.close();
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof PathHolder that && that.upload == this.upload;
		}

		@Override
		public int hashCode() {
			return System.identityHashCode(this.upload);
		}
	}

	/**
	 * A stream of exactly {@code n} bytes of a repeating pattern, read in
	 * 8 KiB chunks by the spooler; avoids allocating a boxed collection.
	 */
	private static final class BigInputStream extends InputStream {

		private final long total;
		private long remaining;

		BigInputStream(long total) {
			this.total = total;
			this.remaining = total;
		}

		@Override
		public int read() {
			if (this.remaining <= 0) {
				return -1;
			}
			this.remaining--;
			return (int) ('a' + (this.total % 26));
		}

		@Override
		public int read(byte[] b, int off, int len) {
			if (this.remaining <= 0) {
				return -1;
			}
			int n = (int) Math.min(len, this.remaining);
			for (int i = 0; i < n; i++) {
				b[off + i] = (byte) ('a' + ((this.total - this.remaining + i) % 26));
			}
			this.remaining -= n;
			return n;
		}

	}

}
