package horse.sumomo.pos_doc_backend.ingestion.archive;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import horse.sumomo.pos_doc_backend.ingestion.api.UploadLimitsProperties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ZipArchiveValidator}. Archives are built
 * programmatically with {@link ZipOutputStream}; PDF fixtures are the
 * minimal synthetic bytes beginning with {@code %PDF-}.
 */
class ZipArchiveValidatorTest {

	private static final byte[] PDF = "%PDF-1.4\n% dummy test document\n%%EOF\n".getBytes(StandardCharsets.UTF_8);

	private static final UploadLimitsProperties DEFAULTS = new UploadLimitsProperties(10485760L, 262144000L,
			52428800L, 100, 100);

	private Path spooledFile;

	@AfterEach
	void deleteSpooledFile() {
		if (this.spooledFile != null) {
			try {
				Files.deleteIfExists(this.spooledFile);
			}
			catch (IOException ignored) {
				// best effort
			}
		}
		this.spooledFile = null;
	}

	@BeforeEach
	void createSpooledFile() throws IOException {
		this.spooledFile = Files.createTempFile("zip-validator-test-", ".zip");
	}

	// ------------------------------------------------------------------
	// valid archives
	// ------------------------------------------------------------------

	@Test
	void singlePdfIsValid() throws Exception {
		writeZip(Map.of("doc.pdf", PDF));
		ValidatedArchive result = new ZipArchiveValidator(DEFAULTS).validate(this.spooledFile, fileBytes());
		assertEquals(1, result.pdfCount());
		assertEquals((long) PDF.length, result.totalUncompressedBytes());
	}

	@Test
	void multiplePdfsInNestedDirectoriesAreValid() throws Exception {
		Map<String, byte[]> entries = new LinkedHashMap<>();
		entries.put("documents/first.pdf", PDF);
		entries.put("documents/sub/second.pdf", PDF);
		entries.put("documents/other/third.pdf", PDF);
		writeZip(entries);
		ValidatedArchive result = new ZipArchiveValidator(DEFAULTS).validate(this.spooledFile, fileBytes());
		assertEquals(3, result.pdfCount());
		assertEquals(3L * PDF.length, result.totalUncompressedBytes());
	}

	@Test
	void directoryEntriesAreAllowedAndDoNotCount() throws Exception {
		writeZip(new Entry("documents/", new byte[0]), new Entry("documents/first.pdf", PDF));
		ValidatedArchive result = new ZipArchiveValidator(DEFAULTS).validate(this.spooledFile, fileBytes());
		assertEquals(1, result.pdfCount());
	}

	@Test
	void tempFileCanBeDeletedAfterValidationOnWindows() throws Exception {
		writeZip(Map.of("doc.pdf", PDF));
		new ZipArchiveValidator(DEFAULTS).validate(this.spooledFile, fileBytes());
		// If any stream or reader were left open, deletion would fail on
		// Windows.
		boolean deleted = Files.deleteIfExists(this.spooledFile);
		this.spooledFile = null;
		assertTrue(deleted, "spooled file must be deletable after validation");
	}

	// ------------------------------------------------------------------
	// structural rejections
	// ------------------------------------------------------------------

	@Test
	void emptyZipFailsBecauseItHasNoPdfs() throws Exception {
		writeZip(Map.of());
		ArchiveValidationException e = assertThrows(ArchiveValidationException.class,
				() -> new ZipArchiveValidator(DEFAULTS).validate(this.spooledFile, fileBytes()));
		assertEquals(ArchiveValidationException.Category.INVALID_ARCHIVE, e.getCategory());
	}

	@Test
	void directoryOnlyZipFailsBecauseItHasNoPdfs() throws Exception {
		this.spooledFile = Files.createTempFile("zip-validator-test-", ".zip");
		try (ZipOutputStream out = zipOut()) {
			out.putNextEntry(new ZipEntry("documents/"));
			out.closeEntry();
		}
		assertThrows(ArchiveValidationException.class,
				() -> new ZipArchiveValidator(DEFAULTS).validate(this.spooledFile, fileBytes()));
	}

	@Test
	void nonZipBytesAreRejectedAsUnsupportedArchiveType() throws Exception {
		// Four or more bytes that are not a recognized ZIP signature are an
		// unsupported archive type (415), not a malformed archive (422).
		Files.write(this.spooledFile, "this is definitely not a zip archive at all".getBytes(StandardCharsets.UTF_8));
		ArchiveValidationException e = assertThrows(ArchiveValidationException.class,
				() -> new ZipArchiveValidator(DEFAULTS).validate(this.spooledFile, fileBytes()));
		assertEquals(ArchiveValidationException.Category.UNSUPPORTED_ARCHIVE_TYPE, e.getCategory());
	}

	@Test
	void fileTooSmallForASignatureIsRejectedAsInvalidArchive() throws Exception {
		// Fewer than four bytes cannot carry any ZIP signature, so the file
		// is malformed/truncated (422), not an unsupported type (415).
		Files.write(this.spooledFile, new byte[] { 'P', 'K', 0x03 });
		ArchiveValidationException e = assertThrows(ArchiveValidationException.class,
				() -> new ZipArchiveValidator(DEFAULTS).validate(this.spooledFile, fileBytes()));
		assertEquals(ArchiveValidationException.Category.INVALID_ARCHIVE, e.getCategory());
	}

	@Test
	void truncatedZipIsRejected() throws Exception {
		writeZip(Map.of("doc.pdf", PDF));
		byte[] bytes = Files.readAllBytes(this.spooledFile);
		Files.write(this.spooledFile, java.util.Arrays.copyOf(bytes, bytes.length / 2));
		assertThrows(ArchiveValidationException.class,
				() -> new ZipArchiveValidator(DEFAULTS).validate(this.spooledFile, fileBytes()));
	}

	@Test
	void nonPdfEntryIsRejected() throws Exception {
		writeZip(Map.of("doc.txt", "text".getBytes(StandardCharsets.UTF_8)));
		assertThrows(ArchiveValidationException.class,
				() -> new ZipArchiveValidator(DEFAULTS).validate(this.spooledFile, fileBytes()));
	}

	@Test
	void pdfNamedEntryWithoutPdfMagicIsRejected() throws Exception {
		writeZip(Map.of("doc.pdf", "not-a-pdf-bytes".getBytes(StandardCharsets.UTF_8)));
		assertThrows(ArchiveValidationException.class,
				() -> new ZipArchiveValidator(DEFAULTS).validate(this.spooledFile, fileBytes()));
	}

	@Test
	void absoluteEntryPathIsRejected() throws Exception {
		writeZip(Map.of("/abs/doc.pdf", PDF));
		assertThrows(ArchiveValidationException.class,
				() -> new ZipArchiveValidator(DEFAULTS).validate(this.spooledFile, fileBytes()));
	}

	@Test
	void forwardSlashTraversalIsRejected() throws Exception {
		writeZip(Map.of("a/../b.pdf", PDF));
		assertThrows(ArchiveValidationException.class,
				() -> new ZipArchiveValidator(DEFAULTS).validate(this.spooledFile, fileBytes()));
	}

	@Test
	void backslashTraversalIsRejected() throws Exception {
		writeZip(Map.of("a\\..\\b.pdf", PDF));
		assertThrows(ArchiveValidationException.class,
				() -> new ZipArchiveValidator(DEFAULTS).validate(this.spooledFile, fileBytes()));
	}

	@Test
	void dotSegmentIsRejected() throws Exception {
		writeZip(Map.of("a/./b.pdf", PDF));
		assertThrows(ArchiveValidationException.class,
				() -> new ZipArchiveValidator(DEFAULTS).validate(this.spooledFile, fileBytes()));
	}

	@Test
	void emptyPathSegmentIsRejected() throws Exception {
		writeZip(Map.of("a//b.pdf", PDF));
		assertThrows(ArchiveValidationException.class,
				() -> new ZipArchiveValidator(DEFAULTS).validate(this.spooledFile, fileBytes()));
	}

	@Test
	void windowsDrivePrefixedEntryIsRejected() throws Exception {
		writeZip(Map.of("C:/doc.pdf", PDF));
		assertThrows(ArchiveValidationException.class,
				() -> new ZipArchiveValidator(DEFAULTS).validate(this.spooledFile, fileBytes()));
	}

	@Test
	void duplicateExactEntryPathIsRejected() throws Exception {
		// The JDK ZipOutputStream refuses to write two entries with the same
		// name, so the archive is built from raw stored entries.
		writeRawZipWithStoredEntries(new String[] { "a.pdf", "a.pdf" }, PDF);
		assertThrows(ArchiveValidationException.class,
				() -> new ZipArchiveValidator(DEFAULTS).validate(this.spooledFile, fileBytes()));
	}

	@Test
	void duplicateCaseInsensitiveEntryPathIsRejected() throws Exception {
		writeZip(new Entry("documents/a.pdf", PDF), new Entry("documents/A.PDF", PDF));
		assertThrows(ArchiveValidationException.class,
				() -> new ZipArchiveValidator(DEFAULTS).validate(this.spooledFile, fileBytes()));
	}

	@Test
	void tooManyFileEntriesAreRejected() throws Exception {
		Map<String, byte[]> entries = new LinkedHashMap<>();
		for (int i = 0; i <= DEFAULTS.maxFileEntries(); i++) {
			entries.put("entry-" + i + ".pdf", PDF);
		}
		writeZip(entries);
		assertThrows(ArchiveValidationException.class,
				() -> new ZipArchiveValidator(DEFAULTS).validate(this.spooledFile, fileBytes()));
	}

	// ------------------------------------------------------------------
	// size and ratio limits (small configured limits keep fixtures tiny)
	// ------------------------------------------------------------------

	@Test
	void perEntryUncompressedLimitIsEnforced() throws Exception {
		UploadLimitsProperties small = new UploadLimitsProperties(10485760L, 262144000L, PDF.length, 100, 100);
		writeZip(Map.of("doc.pdf", java.util.Arrays.copyOf(PDF, PDF.length + 1)));
		assertThrows(ArchiveValidationException.class,
				() -> new ZipArchiveValidator(small).validate(this.spooledFile, fileBytes()));
	}

	@Test
	void perEntryReadStopsAtTheFirstLimitBreakingChunk() throws Exception {
		// The bounded buffer is read in BUFFER_SIZE chunks. With a per-entry
		// limit of exactly one buffer's worth of payload (after the 5-byte
		// PDF magic), the read must abort on the second chunk and must not
		// inflate the rest of the (much larger) entry.
		int buffer = ZipArchiveValidator.BUFFER_SIZE;
		long limit = buffer - 5L; // magic (5) + one full buffer chunk
		int totalPayload = 8 * buffer; // far beyond the limit
		byte[] content = new byte[totalPayload];
		System.arraycopy(PDF, 0, content, 0, PDF.length);
		java.util.Arrays.fill(content, PDF.length, totalPayload, (byte) 'a');

		CountingInputStream counting = new CountingInputStream(new java.io.ByteArrayInputStream(content));
		ArchiveValidationException e = assertThrows(ArchiveValidationException.class,
				() -> ZipArchiveValidator.readAndValidatePdf(counting, limit));
		assertEquals(ArchiveValidationException.Category.INVALID_ARCHIVE, e.getCategory());
		// Magic (5) + the first full chunk that stays within the limit. The
		// second chunk would push the total past the limit, so it must never
		// be read.
		assertEquals(5L + buffer, counting.count,
				"the read must stop at the first limit-breaking chunk");
	}

	@Test
	void totalUncompressedLimitIsEnforced() throws Exception {
		// Each entry fits the per-entry limit (== 34 bytes) but the running
		// total of two entries (68 bytes) exceeds the 34-byte budget.
		UploadLimitsProperties small = new UploadLimitsProperties(10485760L, PDF.length, PDF.length, 100, 100);
		Map<String, byte[]> entries = new LinkedHashMap<>();
		entries.put("a.pdf", PDF);
		entries.put("b.pdf", PDF);
		writeZip(entries);
		assertThrows(ArchiveValidationException.class,
				() -> new ZipArchiveValidator(small).validate(this.spooledFile, fileBytes()));
	}

	@Test
	void compressionRatioLimitIsEnforced() throws Exception {
		// One MiB of a single repeated byte is extremely compressible: the
		// archive is tiny, so the uncompressed/compressed ratio far exceeds
		// the limit once at least one compressed byte is known.
		byte[] highlyCompressible = new byte[1024 * 1024];
		java.util.Arrays.fill(highlyCompressible, (byte) 'a');
		writeZip(Map.of("big.pdf", join(PDF, highlyCompressible)));
		assertThrows(ArchiveValidationException.class,
				() -> new ZipArchiveValidator(DEFAULTS).validate(this.spooledFile, fileBytes()));
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	/**
	 * One named entry for the programmatic ZIP builder.
	 */
	private record Entry(String name, byte[] content) {
	}

	private void writeZip(Map<String, byte[]> entries) throws IOException {
		writeZip(entries.entrySet().stream()
				.map(e -> new Entry(e.getKey(), e.getValue()))
				.toList());
	}

	private void writeZip(Entry... entries) throws IOException {
		writeZip(java.util.List.of(entries));
	}

	private void writeZip(java.util.List<Entry> entries) throws IOException {
		this.spooledFile = Files.createTempFile("zip-validator-test-", ".zip");
		try (ZipOutputStream out = zipOut()) {
			for (Entry entry : entries) {
				ZipEntry zipEntry = new ZipEntry(entry.name());
				if (entry.name().endsWith("/")) {
					zipEntry.setTime(0L);
				}
				else {
					zipEntry.setMethod(ZipEntry.DEFLATED);
				}
				out.putNextEntry(zipEntry);
				out.write(entry.content());
				out.closeEntry();
			}
		}
	}

	private ZipOutputStream zipOut() throws IOException {
		return new ZipOutputStream(Files.newOutputStream(this.spooledFile));
	}

	private long fileBytes() throws IOException {
		return Files.size(this.spooledFile);
	}

	private static byte[] join(byte[] prefix, byte[] rest) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.writeBytes(prefix);
		out.writeBytes(rest);
		return out.toByteArray();
	}

	/**
	 * Builds a raw ZIP from STORE (uncompressed) entries, allowing names the
	 * JDK writer would refuse (e.g. exact duplicates). Little-endian fields
	 * are written explicitly.
	 */
	private void writeRawZipWithStoredEntries(String[] names, byte[] content) throws IOException {
		this.spooledFile = Files.createTempFile("zip-validator-test-", ".zip");
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		long[] offsets = new long[names.length];
		java.util.zip.CRC32 crc32 = new java.util.zip.CRC32();
		crc32.update(content);
		long crcValue = crc32.getValue();
		int dosTime = 0;
		int dosDate = 0x0021; // 1980-01-01

		for (int i = 0; i < names.length; i++) {
			byte[] nameBytes = names[i].getBytes(StandardCharsets.UTF_8);
			offsets[i] = out.size();
			writeLe32(out, 0x04034b50);
			writeLe16(out, 20);
			writeLe16(out, 0);
			writeLe16(out, 0); // stored
			writeLe16(out, dosTime);
			writeLe16(out, dosDate);
			writeLe32(out, (int) crcValue);
			writeLe32(out, content.length);
			writeLe32(out, content.length);
			writeLe16(out, nameBytes.length);
			writeLe16(out, 0);
			out.writeBytes(nameBytes);
			out.writeBytes(content);
		}

		long cdOffset = out.size();
		for (int i = 0; i < names.length; i++) {
			byte[] nameBytes = names[i].getBytes(StandardCharsets.UTF_8);
			writeLe32(out, 0x02014b50);
			writeLe16(out, 20);
			writeLe16(out, 20);
			writeLe16(out, 0);
			writeLe16(out, 0);
			writeLe16(out, dosTime);
			writeLe16(out, dosDate);
			writeLe32(out, (int) crcValue);
			writeLe32(out, content.length);
			writeLe32(out, content.length);
			writeLe16(out, nameBytes.length);
			writeLe16(out, 0); // extra
			writeLe16(out, 0); // comment
			writeLe16(out, 0); // disk
			writeLe16(out, 0); // internal attrs
			writeLe32(out, 0); // external attrs
			writeLe32(out, offsets[i]);
			out.writeBytes(nameBytes);
		}
		long cdSize = out.size() - cdOffset;

		writeLe32(out, 0x06054b50);
		writeLe16(out, 0);
		writeLe16(out, 0);
		writeLe16(out, names.length);
		writeLe16(out, names.length);
		writeLe32(out, (int) cdSize);
		writeLe32(out, (int) cdOffset);
		writeLe16(out, 0);

		Files.write(this.spooledFile, out.toByteArray());
	}

	private static void writeLe16(ByteArrayOutputStream out, int value) {
		out.write(value & 0xFF);
		out.write((value >> 8) & 0xFF);
	}

	private static void writeLe32(ByteArrayOutputStream out, long value) {
		out.write((int) (value & 0xFF));
		out.write((int) ((value >> 8) & 0xFF));
		out.write((int) ((value >> 16) & 0xFF));
		out.write((int) ((value >> 24) & 0xFF));
	}

	/**
	 * An input stream that counts how many bytes were actually read, used to
	 * prove the per-entry read stops at the first limit-breaking chunk.
	 */
	private static final class CountingInputStream extends InputStream {

		private final InputStream delegate;
		long count = 0L;

		private CountingInputStream(InputStream delegate) {
			this.delegate = delegate;
		}

		@Override
		public int read() throws IOException {
			int b = this.delegate.read();
			if (b != -1) {
				this.count++;
			}
			return b;
		}

		@Override
		public int read(byte[] buf, int off, int len) throws IOException {
			int r = this.delegate.read(buf, off, len);
			if (r != -1) {
				this.count += r;
			}
			return r;
		}

	}

}
