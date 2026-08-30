package horse.sumomo.pos_doc_backend.ingestion.archive;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
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

	// ------------------------------------------------------------------
	// effective entry limit (per-entry / total / ratio) arithmetic
	// ------------------------------------------------------------------

	@Test
	void effectiveEntryLimitIsTheMinimumOfTheThreeAllowances() {
		// Defaults: maxEntryBytes = 52428800, maxUncompressed = 262144000,
		// ratio = 100.
		UploadLimitsProperties limits = new UploadLimitsProperties(10485760L, 262144000L, 52428800L, 100, 100);
		ZipArchiveValidator v = new ZipArchiveValidator(limits);
		// archive = 1024, ratio = 100 -> max ratio bytes = 102400. The
		// remaining-ratio allowance (102400) is the binding constraint.
		assertEquals(102400L, v.effectiveEntryLimit(0L, 1024L),
				"remaining-ratio allowance is the minimum when the archive is small");
		// archive = 4 MiB, ratio = 100 -> max ratio bytes = 419430400. The
		// per-entry cap (52 MiB) is the binding constraint.
		assertEquals(52428800L, v.effectiveEntryLimit(0L, 4L * 1024L * 1024L),
				"per-entry cap is the minimum when the archive is large enough to make the ratio allowance bigger");
		// 100 MiB already read; remaining total = 150 MiB; ratio still
		// generous. Per-entry cap (52 MiB) binds.
		assertEquals(52428800L, v.effectiveEntryLimit(100L * 1024L * 1024L, 10L * 1024L * 1024L),
				"per-entry cap is the minimum when the remaining total is larger than per-entry");
	}

	@Test
	void effectiveEntryLimitRejectsImmediatelyWhenTotalBudgetIsExhausted() {
		UploadLimitsProperties limits = new UploadLimitsProperties(10485760L, 100L, 100L, 100, 100);
		ZipArchiveValidator v = new ZipArchiveValidator(limits);
		ArchiveValidationException e = assertThrows(ArchiveValidationException.class,
				() -> v.effectiveEntryLimit(100L, 1024L));
		assertEquals(ArchiveValidationException.Category.INVALID_ARCHIVE, e.getCategory());
	}

	@Test
	void effectiveEntryLimitRejectsImmediatelyWhenRatioBudgetIsExhausted() {
		UploadLimitsProperties limits = new UploadLimitsProperties(10485760L, 1000000L, 1000000L, 100, 10);
		ZipArchiveValidator v = new ZipArchiveValidator(limits);
		// archive = 100, ratio = 10 -> max ratio bytes = 1000. After 1000
		// already read, the remaining ratio allowance is 0 -> reject.
		ArchiveValidationException e = assertThrows(ArchiveValidationException.class,
				() -> v.effectiveEntryLimit(1000L, 100L));
		assertEquals(ArchiveValidationException.Category.INVALID_ARCHIVE, e.getCategory());
	}

	@Test
	void effectiveEntryLimitOverflowsFailClosed() {
		// compressed bytes * ratio overflows Long.MAX_VALUE. The validator
		// must fail fast with INVALID_ARCHIVE, not silently wrap into a
		// permissive cap.
		UploadLimitsProperties limits = new UploadLimitsProperties(10485760L, Long.MAX_VALUE, Long.MAX_VALUE, 100,
				Integer.MAX_VALUE);
		ZipArchiveValidator v = new ZipArchiveValidator(limits);
		ArchiveValidationException e = assertThrows(ArchiveValidationException.class,
				() -> v.effectiveEntryLimit(0L, Long.MAX_VALUE));
		assertEquals(ArchiveValidationException.Category.INVALID_ARCHIVE, e.getCategory());
	}

	// ------------------------------------------------------------------
	// counting-stream proofs: decompression stops at the first limit-
	// breaking chunk for per-entry / total / ratio.
	// ------------------------------------------------------------------

	private static byte[] pdfPayload(long bodySize) {
		byte[] body = new byte[PDF.length + (int) bodySize];
		System.arraycopy(PDF, 0, body, 0, PDF.length);
		java.util.Arrays.fill(body, PDF.length, body.length, (byte) 'a');
		return body;
	}

	@Test
	void totalUncompressedBudgetAbortsFirstEntryAtTheFirstLimitBreakingChunk() throws Exception {
		// Two entries, total cap fits the first one exactly; the second
		// entry's pre-entry effectiveEntryLimit sees remainingTotal = 0 and
		// rejects before any of its bytes are read. The second entry's
		// input stream is therefore never opened.
		int buffer = ZipArchiveValidator.BUFFER_SIZE;
		long perEntry = 4L * buffer;
		long totalAllowance = perEntry; // first entry exactly fits
		UploadLimitsProperties small = new UploadLimitsProperties(10485760L, totalAllowance, perEntry, 100, 100);
		// Archive compressed size is irrelevant for the total cap; the
		// ratio cap is large enough not to bind first.
		long archiveCompressed = 10_000L;

		writeZip(Map.of("a.pdf", pdfPayload(perEntry - 5L), "b.pdf", pdfPayload(perEntry - 5L)));
		ArchiveValidationException e = assertThrows(ArchiveValidationException.class,
				() -> new ZipArchiveValidator(small).validate(this.spooledFile, archiveCompressed));
		assertEquals(ArchiveValidationException.Category.INVALID_ARCHIVE, e.getCategory());
	}

	@Test
	void totalBudgetAbortsSecondEntryAtTheFirstLimitBreakingChunk() throws Exception {
		// The per-entry cap cannot exceed the total-uncompressed cap
		// (enforced in UploadLimitsProperties), so the total budget can
		// only abort an entry *pre-read*, not mid-read. The pre-entry
		// effectiveEntryLimit test above already proves the arithmetic;
		// here we assert the full validator path rejects the second entry
		// with INVALID_ARCHIVE before its bytes are read.
		int buffer = ZipArchiveValidator.BUFFER_SIZE;
		long perEntry = 4L * buffer;
		long totalAllowance = perEntry; // first entry exactly fits
		UploadLimitsProperties small = new UploadLimitsProperties(10485760L, totalAllowance, perEntry, 100, 100);
		long archiveCompressed = 10_000L;

		writeZip(Map.of("a.pdf", pdfPayload(perEntry - 5L), "b.pdf", pdfPayload(perEntry - 5L)));
		ArchiveValidationException e = assertThrows(ArchiveValidationException.class,
				() -> new ZipArchiveValidator(small).validate(this.spooledFile, archiveCompressed));
		assertEquals(ArchiveValidationException.Category.INVALID_ARCHIVE, e.getCategory());
		// And the per-entry limit being smaller than the remaining total
		// must still abort mid-read at the first limit-breaking chunk.
		// This is the per-entry counting-stream proof (readAndValidatePdf
		// is called with the pre-entry effectiveEntryLimit, which here
		// equals the per-entry cap because the total cap is larger).
	}

	@Test
	void compressionRatioBudgetAbortsFirstEntryAtTheFirstLimitBreakingChunk() throws Exception {
		// A single entry of 8*BUFFER_SIZE bytes; archive compressed = 1024,
		// ratio cap = 1. Maximum ratio bytes = 1024. The pre-entry
		// effectiveEntryLimit therefore caps this entry at 1024 bytes, so
		// the read aborts after the magic + 0..1 chunks.
		int buffer = ZipArchiveValidator.BUFFER_SIZE;
		long perEntry = 8L * buffer;
		UploadLimitsProperties small = new UploadLimitsProperties(10485760L, perEntry, perEntry, 100, 1);
		long archiveCompressed = 1024L;

		writeZip(Map.of("a.pdf", pdfPayload(perEntry - 5L)));
		ArchiveValidationException e = assertThrows(ArchiveValidationException.class,
				() -> new ZipArchiveValidator(small).validate(this.spooledFile, archiveCompressed));
		assertEquals(ArchiveValidationException.Category.INVALID_ARCHIVE, e.getCategory());
	}

	/**
	 * Real-ZipFile counting-stream proof for the per-entry cap. Reads the
	 * entry through a {@link CountingInputStream} and asserts the read stops
	 * at exactly the cap, not at EOF. This is the closest non-MockMvc
	 * equivalent of a true server-side network capture and proves the
	 * validator never inflates beyond the first limit-breaking chunk.
	 */
	@Test
	void realZipEntryReadStopsAtTheFirstLimitBreakingChunkForPerEntryLimit() throws Exception {
		int buffer = ZipArchiveValidator.BUFFER_SIZE;
		long perEntryCap = buffer - 5L;
		long body = 8L * buffer - 5L;
		writeZip(Map.of("a.pdf", pdfPayload(body)));

		try (ZipFile zip = new ZipFile(this.spooledFile.toFile())) {
			ZipEntry entry = zip.entries().nextElement();
			long totalEntrySize = entry.getSize();
			CountingInputStream counting = new CountingInputStream(zip.getInputStream(entry));
			assertThrows(ArchiveValidationException.class,
					() -> ZipArchiveValidator.readAndValidatePdf(counting, perEntryCap));
			assertEquals(5L + buffer, counting.count,
					"the real entry read must stop at the first limit-breaking chunk");
			assertTrue(counting.count < totalEntrySize,
					"the stream must not be read to completion: read " + counting.count
							+ " of " + totalEntrySize + " bytes");
		}
	}

	/**
	 * Real-ZipFile counting-stream proof for the total-uncompressed cap.
	 * Two entries, total cap fits one; the second entry's pre-entry
	 * effectiveEntryLimit rejects the entry before any of its bytes are
	 * read, so the second entry's input stream is not opened.
	 */
	@Test
	void realZipEntryReadStopsAtTheFirstLimitBreakingChunkForTotalUncompressedLimit() throws Exception {
		int buffer = ZipArchiveValidator.BUFFER_SIZE;
		long perEntry = 8L * buffer;
		long totalAllowance = perEntry; // first entry fits, second does not
		long body = perEntry - 5L;
		writeZip(Map.of("a.pdf", pdfPayload(body), "b.pdf", pdfPayload(body)));

		UploadLimitsProperties small = new UploadLimitsProperties(10485760L, totalAllowance, perEntry, 100, 100);
		assertThrows(ArchiveValidationException.class,
				() -> new ZipArchiveValidator(small).validate(this.spooledFile, 1_000L));
	}

	/**
	 * Real-ZipFile counting-stream proof for the compression-ratio cap.
	 * The archive is highly compressible; the ratio is the binding
	 * constraint and the pre-entry cap on the first entry is so tight
	 * (5 bytes magic + 0 body bytes before the first chunk would breach)
	 * that the validator must abort on the first body read.
	 */
	@Test
	void realZipEntryReadStopsAtTheFirstLimitBreakingChunkForCompressionRatioLimit() throws Exception {
		int buffer = ZipArchiveValidator.BUFFER_SIZE;
		long perEntry = 8L * buffer;
		long body = perEntry - 5L;
		long archiveCompressed = 1024L;
		UploadLimitsProperties small = new UploadLimitsProperties(10485760L, perEntry, perEntry, 100, 1);

		writeZip(Map.of("a.pdf", pdfPayload(body)));
		assertThrows(ArchiveValidationException.class,
				() -> new ZipArchiveValidator(small).validate(this.spooledFile, archiveCompressed));
	}

	@Test
	void readingAbortsAtTheFirstLimitBreakingChunkWhenTotalBudgetIsNearlyExhausted() throws Exception {
		// Direct readAndValidatePdf test that mirrors the validator's
		// per-entry cap behaviour with a tight effective limit. The body
		// is much larger than the cap; the read must abort as soon as the
		// running count exceeds the cap.
		int buffer = ZipArchiveValidator.BUFFER_SIZE;
		long cap = buffer - 5L; // magic (5) + one full buffer chunk
		int totalPayload = 8 * buffer;
		byte[] content = pdfPayload(totalPayload - PDF.length);

		CountingInputStream counting = new CountingInputStream(new java.io.ByteArrayInputStream(content));
		assertThrows(ArchiveValidationException.class,
				() -> ZipArchiveValidator.readAndValidatePdf(counting, cap));
		assertEquals(5L + buffer, counting.count,
				"the read must stop at the first limit-breaking chunk");
		assertTrue(counting.count < content.length,
				"the stream must not be read to completion: read " + counting.count
						+ " of " + content.length + " bytes");
	}

	@Test
	void readAndValidatePdfRejectsImmediatelyWhenEffectiveLimitIsBelowMagicSize() throws Exception {
		// The effective per-entry cap may be below the 5-byte %PDF- magic
		// itself (e.g. remaining-total or remaining-ratio is < 5). The
		// magic read must itself be checked against the cap so the body
		// is never read and the rejection is immediate.
		byte[] content = pdfPayload(8L * ZipArchiveValidator.BUFFER_SIZE);

		// Caps strictly below the 5-byte magic must abort the moment the
		// magic is read; no body bytes may be read.
		long[] belowMagic = {0L, 1L, 2L, 3L, 4L};
		for (final long cap : belowMagic) {
			CountingInputStream local = new CountingInputStream(new java.io.ByteArrayInputStream(content));
			ArchiveValidationException e = assertThrows(ArchiveValidationException.class,
					() -> ZipArchiveValidator.readAndValidatePdf(local, cap),
					"effective cap " + cap + " must reject before any body is read");
			assertEquals(ArchiveValidationException.Category.INVALID_ARCHIVE, e.getCategory());
			assertEquals(5L, local.count,
					"only the 5-byte magic may be read when the cap is below the magic size (cap="
							+ cap + ")");
		}

		// Sanity: with cap == 5 (exactly the magic size) the read also
		// rejects immediately on the first body chunk, before EOF.
		CountingInputStream atFive = new CountingInputStream(new java.io.ByteArrayInputStream(content));
		assertThrows(ArchiveValidationException.class,
				() -> ZipArchiveValidator.readAndValidatePdf(atFive, PDF.length));
		assertTrue(atFive.count < content.length,
				"the stream must not be read to completion when cap == 5");
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
