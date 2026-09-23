package horse.sumomo.pos_doc_backend.ingestion.archive;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PdfEntryDecoder}.
 *
 * <p>The tests use synthetic PDF payloads (bytes beginning with {@code %PDF-})
 * and construct the Java-serialized {@code byte[]} envelope with
 * {@link java.io.ObjectOutputStream} — which is permitted for <em>test input
 * creation only</em>. The decoder itself never uses {@code ObjectInputStream}
 * or any general deserialization.
 */
class PdfEntryDecoderTest {

	private static final byte[] PDF = "%PDF-1.4\n% synthetic test document\n%%EOF\n".getBytes(StandardCharsets.UTF_8);
	private static final long GENEROUS_CAP = 1_000_000L;

	// ------------------------------------------------------------------
	// 1. raw PDF returned byte-for-byte
	// ------------------------------------------------------------------

	@Test
	void rawPdfIsReturnedByteForByteUnchanged() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		PdfEntryDecoder.DecodeResult r = new PdfEntryDecoder().decode(stream(PDF), out, GENEROUS_CAP);
		assertEquals(PdfEntryDecoder.Encoding.RAW_PDF, r.encoding());
		assertArrayEquals(PDF, out.toByteArray());
		assertEquals(PDF.length, r.sourceBytesRead());
		assertEquals(PDF.length, r.pdfBytesWritten());
	}

	// ------------------------------------------------------------------
	// 2-4. valid serialized byte[] accepted, unwrapped, counts reported
	// ------------------------------------------------------------------

	@Test
	void validSerializedByteArrayIsAcceptedAndUnwrapped() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		PdfEntryDecoder.DecodeResult r = new PdfEntryDecoder().decode(stream(serialize(PDF)), out, GENEROUS_CAP);
		assertEquals(PdfEntryDecoder.Encoding.JAVA_SERIALIZED_BYTE_ARRAY, r.encoding());
		assertArrayEquals(PDF, out.toByteArray());
	}

	@Test
	void wrappedResultReportsSourceBytesReadAsPayloadPlus27() throws Exception {
		PdfEntryDecoder.DecodeResult r = new PdfEntryDecoder()
				.decode(stream(serialize(PDF)), new ByteArrayOutputStream(), GENEROUS_CAP);
		assertEquals(PDF.length + 27L, r.sourceBytesRead());
	}

	@Test
	void wrappedResultReportsPdfBytesWrittenAsPayloadLength() throws Exception {
		PdfEntryDecoder.DecodeResult r = new PdfEntryDecoder()
				.decode(stream(serialize(PDF)), new ByteArrayOutputStream(), GENEROUS_CAP);
		assertEquals(PDF.length, r.pdfBytesWritten());
	}

	// ------------------------------------------------------------------
	// 5. exact 23-byte prefix + valid length + PDF succeeds (manual build)
	// ------------------------------------------------------------------

	@Test
	void exactPrefixWithValidLengthAndPdfSucceeds() throws Exception {
		byte[] manual = envelope(prefix23(), PDF.length, PDF);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		PdfEntryDecoder.DecodeResult r = new PdfEntryDecoder().decode(stream(manual), out, GENEROUS_CAP);
		assertEquals(PdfEntryDecoder.Encoding.JAVA_SERIALIZED_BYTE_ARRAY, r.encoding());
		assertArrayEquals(PDF, out.toByteArray());
		assertEquals(PDF.length + 27L, r.sourceBytesRead());
	}

	// ------------------------------------------------------------------
	// 6. truncated envelope rejected
	// ------------------------------------------------------------------

	@Test
	void truncatedEnvelopeIsRejected() throws Exception {
		// Cut inside the 27-byte header.
		byte[] truncated = Arrays.copyOf(serialize(PDF), 20);
		assertThrows(ArchiveValidationException.class,
				() -> new PdfEntryDecoder().decode(stream(truncated), new ByteArrayOutputStream(), GENEROUS_CAP));
	}

	// ------------------------------------------------------------------
	// 7. incorrect class descriptor / prefix rejected
	// ------------------------------------------------------------------

	@Test
	void incorrectPrefixIsRejected() throws Exception {
		byte[] prefix = prefix23();
		prefix[10] = 0x00; // corrupt a byte that is not in the first 5
		byte[] bad = envelope(prefix, PDF.length, PDF);
		assertThrows(ArchiveValidationException.class,
				() -> new PdfEntryDecoder().decode(stream(bad), new ByteArrayOutputStream(), GENEROUS_CAP));
	}

	// ------------------------------------------------------------------
	// 8. negative declared array length rejected
	// ------------------------------------------------------------------

	@Test
	void negativeDeclaredLengthIsRejected() throws Exception {
		byte[] bad = envelope(prefix23(), -1, PDF);
		assertThrows(ArchiveValidationException.class,
				() -> new PdfEntryDecoder().decode(stream(bad), new ByteArrayOutputStream(), GENEROUS_CAP));
	}

	// ------------------------------------------------------------------
	// 9. declared length smaller than actual payload -> trailing bytes
	// ------------------------------------------------------------------

	@Test
	void declaredLengthSmallerThanActualPayloadIsRejected() throws Exception {
		// Declare (PDF.length - 1) but provide the full PDF, leaving one
		// trailing byte after the declared payload.
		byte[] bad = envelope(prefix23(), PDF.length - 1, PDF);
		assertThrows(ArchiveValidationException.class,
				() -> new PdfEntryDecoder().decode(stream(bad), new ByteArrayOutputStream(), GENEROUS_CAP));
	}

	// ------------------------------------------------------------------
	// 10. declared length larger than available payload -> truncated
	// ------------------------------------------------------------------

	@Test
	void declaredLengthLargerThanAvailablePayloadIsRejected() throws Exception {
		// Declare (PDF.length + 10) but provide only the PDF, so EOF is hit
		// before the declared payload is complete.
		byte[] bad = envelope(prefix23(), PDF.length + 10, PDF);
		assertThrows(ArchiveValidationException.class,
				() -> new PdfEntryDecoder().decode(stream(bad), new ByteArrayOutputStream(), GENEROUS_CAP));
	}

	// ------------------------------------------------------------------
	// 11. declared payload shorter than five bytes rejected
	// ------------------------------------------------------------------

	@Test
	void declaredPayloadShorterThanFiveBytesIsRejected() throws Exception {
		byte[] bad = envelope(prefix23(), 4, new byte[] { '%', 'P', 'D', 'F' });
		assertThrows(ArchiveValidationException.class,
				() -> new PdfEntryDecoder().decode(stream(bad), new ByteArrayOutputStream(), GENEROUS_CAP));
	}

	// ------------------------------------------------------------------
	// 12. wrapped payload not beginning with %PDF- rejected
	// ------------------------------------------------------------------

	@Test
	void wrappedPayloadNotBeginningWithPdfMagicIsRejected() throws Exception {
		byte[] notPdf = new byte[] { 'X', 'Y', 'Z', '1', '2', '3', '4', '5', '6', '7' };
		byte[] bad = envelope(prefix23(), notPdf.length, notPdf);
		assertThrows(ArchiveValidationException.class,
				() -> new PdfEntryDecoder().decode(stream(bad), new ByteArrayOutputStream(), GENEROUS_CAP));
	}

	// ------------------------------------------------------------------
	// 13. arbitrary leading junk followed by %PDF- rejected
	// ------------------------------------------------------------------

	@Test
	void arbitraryLeadingJunkFollowedByPdfMagicIsRejected() throws Exception {
		byte[] junk = "JUNKJUNK".getBytes(StandardCharsets.UTF_8);
		byte[] content = new byte[junk.length + PDF.length];
		System.arraycopy(junk, 0, content, 0, junk.length);
		System.arraycopy(PDF, 0, content, junk.length, PDF.length);
		assertThrows(ArchiveValidationException.class,
				() -> new PdfEntryDecoder().decode(stream(content), new ByteArrayOutputStream(), GENEROUS_CAP));
	}

	// ------------------------------------------------------------------
	// 14. source byte limit enforced during streaming (raw + wrapped)
	// ------------------------------------------------------------------

	@Test
	void sourceByteLimitIsEnforcedDuringStreaming() throws Exception {
		// Raw: a body larger than the cap is rejected mid-stream.
		byte[] bigPdf = new byte[200];
		System.arraycopy(PDF, 0, bigPdf, 0, PDF.length);
		Arrays.fill(bigPdf, PDF.length, bigPdf.length, (byte) 'a');
		assertThrows(ArchiveValidationException.class,
				() -> new PdfEntryDecoder().decode(stream(bigPdf), new ByteArrayOutputStream(), 100));

		// Wrapped: the cap includes the 27-byte envelope. The wrapped source is
		// (27 + PDF.length) bytes; a cap one below that rejects and an exact
		// cap accepts.
		byte[] wrapped = serialize(PDF);
		long wrappedSize = wrapped.length;
		assertThrows(ArchiveValidationException.class,
				() -> new PdfEntryDecoder().decode(stream(wrapped), new ByteArrayOutputStream(), wrappedSize - 1));
		PdfEntryDecoder.DecodeResult ok = new PdfEntryDecoder()
				.decode(stream(wrapped), new ByteArrayOutputStream(), wrappedSize);
		assertEquals(PdfEntryDecoder.Encoding.JAVA_SERIALIZED_BYTE_ARRAY, ok.encoding());
	}

	// ------------------------------------------------------------------
	// 15. decoder stops shortly after the limit, not at EOF
	// ------------------------------------------------------------------

	@Test
	void decoderStopsShortlyAfterLimitExceeded() throws Exception {
		int total = 8 * 8192;
		byte[] bigPdf = new byte[total];
		System.arraycopy(PDF, 0, bigPdf, 0, PDF.length);
		Arrays.fill(bigPdf, PDF.length, total, (byte) 'a');

		CountingInputStream counting = new CountingInputStream(new ByteArrayInputStream(bigPdf));
		long limit = 8192 - 5; // magic (5) + one full 8192 chunk exceeds the cap
		assertThrows(ArchiveValidationException.class,
				() -> new PdfEntryDecoder().decode(counting, new ByteArrayOutputStream(), limit));
		// The read must stop at the first limit-breaking chunk, not at EOF.
		assertEquals(5L + 8192, counting.count,
				"the decoder must stop at the first limit-breaking chunk");
		assertTrue(counting.count < bigPdf.length,
				"the stream must not be read to completion: read " + counting.count + " of " + bigPdf.length);
	}

	// ------------------------------------------------------------------
	// 16. empty input rejected
	// ------------------------------------------------------------------

	@Test
	void emptyInputIsRejected() throws Exception {
		assertThrows(ArchiveValidationException.class,
				() -> new PdfEntryDecoder().decode(stream(new byte[0]), new ByteArrayOutputStream(), GENEROUS_CAP));
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	private static InputStream stream(byte[] bytes) {
		return new ByteArrayInputStream(bytes);
	}

	/** Builds a strict Java-serialized {@code byte[]} envelope (test input only). */
	private static byte[] serialize(byte[] pdfBytes) {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		try (java.io.ObjectOutputStream oos = new java.io.ObjectOutputStream(bos)) {
			oos.writeObject(Objects.requireNonNull(pdfBytes));
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return bos.toByteArray();
	}

	/** The exact 23-byte class-descriptor prefix (derived from a real envelope). */
	private static byte[] prefix23() {
		return Arrays.copyOf(serialize(PDF), 23);
	}

	/** Builds a manual envelope: 23-byte prefix + 4-byte signed BE length + payload. */
	private static byte[] envelope(byte[] prefix, int declaredLength, byte[] payload) {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		bos.writeBytes(prefix);
		bos.write((declaredLength >>> 24) & 0xFF);
		bos.write((declaredLength >>> 16) & 0xFF);
		bos.write((declaredLength >>> 8) & 0xFF);
		bos.write(declaredLength & 0xFF);
		bos.writeBytes(payload);
		return bos.toByteArray();
	}

	/** An input stream that counts how many bytes were actually read. */
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
