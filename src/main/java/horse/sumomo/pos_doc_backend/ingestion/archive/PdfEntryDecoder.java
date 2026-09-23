package horse.sumomo.pos_doc_backend.ingestion.archive;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * Shared streaming decoder for PDF entries in source archives.
 *
 * <p>A source archive may carry each PDF entry in one of exactly two
 * encodings:
 * <ul>
 *   <li>{@link Encoding#RAW_PDF} — the entry begins at offset {@code 0} with
 *       the PDF signature {@code %PDF-}.</li>
 *   <li>{@link Encoding#JAVA_SERIALIZED_BYTE_ARRAY} — the entry is a Java
 *       serialized {@code byte[]} carrying the PDF, prefixed by a strict
 *       27-byte envelope (a 23-byte fixed class-descriptor prefix followed by
 *       a 4-byte signed big-endian array length); the PDF payload begins at
 *       offset {@code 27}.</li>
 * </ul>
 *
 * <p>The serialized form is decoded <em>strictly</em>: the exact 23-byte
 * prefix, a non-negative declared length of at least {@code 5}, a payload that
 * begins with {@code %PDF-}, no bytes after the declared payload, and EOF
 * immediately after it. Arbitrary leading junk (including a {@code %PDF-} that
 * appears later than offset {@code 0}) is rejected.
 *
 * <p>The decoder is a <em>bounded stream</em>: it never loads the whole entry
 * into memory and enforces {@code maxSourceBytes} against the raw uncompressed
 * source-entry bytes (for the serialized form this includes the 27-byte
 * envelope).
 *
 * <p>This is the single production implementation of the PDF-entry parsing
 * rules, shared by {@link ZipArchiveValidator} and
 * {@code ArchiveExtractionService}. It parses the one byte-array envelope by
 * hand — it does <strong>not</strong> use {@code ObjectInputStream}, Java
 * deserialization, reflection, or a general serialization library.
 */
public final class PdfEntryDecoder {

	/** The two accepted PDF-entry encodings. */
	public enum Encoding {
		/** Raw PDF bytes beginning at offset {@code 0} with {@code %PDF-}. */
		RAW_PDF,
		/** A strict Java-serialized {@code byte[]} envelope wrapping the PDF. */
		JAVA_SERIALIZED_BYTE_ARRAY
	}

	/**
	 * Result of a successful decode.
	 *
	 * @param encoding the detected encoding
	 * @param sourceBytesRead the number of raw uncompressed source-entry bytes
	 *          consumed; for a serialized entry this includes the 27-byte
	 *          envelope
	 * @param pdfBytesWritten the number of normalized PDF bytes written to the
	 *          output
	 */
	public record DecodeResult(Encoding encoding, long sourceBytesRead, long pdfBytesWritten) {
	}

	// Bounded read buffer. Kept at 8192 so the raw-PDF read pattern (a 5-byte
	// signature followed by 8192-byte chunks, aborting at the first
	// limit-breaking chunk) matches the pre-decoder validator behaviour that
	// the unit tests pin.
	private static final int BUFFER_SIZE = 8192;
	private static final int PDF_MAGIC_LEN = 5;
	private static final int ENVELOPE_PREFIX_LEN = 23;
	private static final int ENVELOPE_HEADER_LEN = 27; // 23-byte prefix + 4-byte length

	private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};
	// The exact 23-byte prefix of a Java-serialized byte[]: stream magic
	// (ACED0005), TC_ARRAY (75), TC_CLASSDESC (72), class name len 2 + "[B"
	// (00 02 5B 42), 8-byte class UID, classDescFlags (02), zero field count
	// (00 00), classDescEnd (78), TC_BLOCKDATALONG (70). The 4-byte signed
	// big-endian array length immediately follows (bytes 23..26); the payload
	// starts at byte 27.
	private static final byte[] ENVELOPE_PREFIX = {
			(byte) 0xAC, (byte) 0xED, 0x00, 0x05,
			0x75, 0x72,
			0x00, 0x02, 0x5B, 0x42,
			(byte) 0xAC, (byte) 0xF3, 0x17, (byte) 0xF8, 0x06, 0x08, 0x54, (byte) 0xE0,
			0x02, 0x00, 0x00,
			0x78, 0x70
	};

	public PdfEntryDecoder() {
	}

	/**
	 * Decodes a single PDF entry, writing the normalized PDF bytes to
	 * {@code normalizedPdfOutput}. The normalized output begins at byte zero
	 * with {@code %PDF-}.
	 *
	 * @param input the uncompressed source-entry stream
	 * @param normalizedPdfOutput the sink for the normalized PDF bytes
	 * @param maxSourceBytes the maximum number of raw uncompressed source-entry
	 *          bytes allowed (including the 27-byte envelope for serialized
	 *          entries); must be non-negative
	 * @return the decode result (encoding + byte counts)
	 * @throws IOException on an underlying I/O failure
	 * @throws ArchiveValidationException with the {@code INVALID_ARCHIVE}
	 *             category when the entry is neither a raw PDF nor a strictly
	 *             valid serialized {@code byte[]}
	 */
	public DecodeResult decode(InputStream input, OutputStream normalizedPdfOutput, long maxSourceBytes)
			throws IOException {
		if (input == null) {
			throw new IllegalArgumentException("input must not be null");
		}
		if (normalizedPdfOutput == null) {
			throw new IllegalArgumentException("normalizedPdfOutput must not be null");
		}
		if (maxSourceBytes < 0) {
			throw new IllegalArgumentException("maxSourceBytes must be >= 0");
		}

		byte[] head = new byte[PDF_MAGIC_LEN];
		readFully(input, head, 0, PDF_MAGIC_LEN, "archive entry is truncated before its header");

		if (startsWith(head, 0, PDF_MAGIC)) {
			return decodeRaw(input, normalizedPdfOutput, head, maxSourceBytes);
		}
		if (startsWith(head, 0, Arrays.copyOf(ENVELOPE_PREFIX, PDF_MAGIC_LEN))) {
			return decodeSerialized(input, normalizedPdfOutput, head, maxSourceBytes);
		}
		throw invalid("archive entry is not a recognizable PDF");
	}

	private DecodeResult decodeRaw(InputStream input, OutputStream out, byte[] head, long maxSourceBytes)
			throws IOException {
		long bytesRead = PDF_MAGIC_LEN;
		// The effective cap may be smaller than the 5-byte signature itself
		// (e.g. when the remaining-total or remaining-ratio allowance is below
		// 5). Reject immediately so the signature read cannot itself exceed the
		// cap; no body bytes are read on this path.
		if (bytesRead > maxSourceBytes) {
			throw invalid("archive entry exceeds the effective size limit");
		}
		out.write(head, 0, PDF_MAGIC_LEN);

		byte[] buffer = new byte[BUFFER_SIZE];
		int read;
		while ((read = input.read(buffer)) != -1) {
			bytesRead += read;
			if (bytesRead > maxSourceBytes) {
				// Stop at the first chunk that pushes this entry over its cap;
				// do not keep inflating the rest.
				throw invalid("archive entry exceeds the per-entry size limit");
			}
			out.write(buffer, 0, read);
		}
		return new DecodeResult(Encoding.RAW_PDF, bytesRead, bytesRead);
	}

	private DecodeResult decodeSerialized(InputStream input, OutputStream out, byte[] head, long maxSourceBytes)
			throws IOException {
		// Assemble the full 27-byte header from the 5 already-read bytes plus
		// the remaining 22.
		byte[] header = new byte[ENVELOPE_HEADER_LEN];
		System.arraycopy(head, 0, header, 0, PDF_MAGIC_LEN);
		readFully(input, header, PDF_MAGIC_LEN, ENVELOPE_HEADER_LEN - PDF_MAGIC_LEN,
				"archive entry is truncated inside its serialized envelope");

		if (!startsWith(header, 0, ENVELOPE_PREFIX)) {
			throw invalid("archive entry does not carry a valid serialized byte-array envelope");
		}

		int declaredLength = ((header[23] & 0xFF) << 24)
				| ((header[24] & 0xFF) << 16)
				| ((header[25] & 0xFF) << 8)
				| (header[26] & 0xFF);
		if (declaredLength < 0) {
			throw invalid("archive entry declares a negative payload length");
		}
		if (declaredLength < PDF_MAGIC_LEN) {
			throw invalid("archive entry declares a payload shorter than the PDF signature");
		}
		long totalSource = (long) ENVELOPE_HEADER_LEN + declaredLength;
		// Enforce the entry limit against the complete uncompressed entry,
		// including the 27-byte envelope.
		if (totalSource > maxSourceBytes) {
			throw invalid("archive entry exceeds the effective size limit");
		}

		// Read the payload. Its first five bytes must be the PDF signature.
		byte[] payloadMagic = new byte[PDF_MAGIC_LEN];
		readFully(input, payloadMagic, 0, PDF_MAGIC_LEN, "archive entry is truncated before its PDF payload");
		if (!startsWith(payloadMagic, 0, PDF_MAGIC)) {
			throw invalid("archive entry payload does not begin with the PDF signature");
		}
		out.write(payloadMagic, 0, PDF_MAGIC_LEN);

		long remaining = declaredLength - PDF_MAGIC_LEN;
		long payloadWritten = PDF_MAGIC_LEN;
		byte[] buffer = new byte[BUFFER_SIZE];
		while (remaining > 0) {
			int want = (int) Math.min(BUFFER_SIZE, remaining);
			readFully(input, buffer, 0, want, "archive entry is truncated before its declared payload is complete");
			out.write(buffer, 0, want);
			payloadWritten += want;
			remaining -= want;
		}

		// The declared payload must be the entire entry: reject trailing bytes.
		if (input.read() != -1) {
			throw invalid("archive entry has trailing bytes after its declared payload");
		}
		return new DecodeResult(Encoding.JAVA_SERIALIZED_BYTE_ARRAY, totalSource, payloadWritten);
	}

	/**
	 * Reads exactly {@code len} bytes into {@code buf[off..off+len)}, throwing
	 * {@code INVALID_ARCHIVE} on premature EOF. Genuine I/O failures propagate
	 * as {@link IOException}.
	 */
	private static void readFully(InputStream in, byte[] buf, int off, int len, String truncatedMessage)
			throws IOException {
		int total = 0;
		while (total < len) {
			int r = in.read(buf, off + total, len - total);
			if (r == -1) {
				throw invalid(truncatedMessage);
			}
			total += r;
		}
	}

	private static boolean startsWith(byte[] actual, int offset, byte[] prefix) {
		if (actual.length - offset < prefix.length) {
			return false;
		}
		for (int i = 0; i < prefix.length; i++) {
			if (actual[offset + i] != prefix[i]) {
				return false;
			}
		}
		return true;
	}

	private static ArchiveValidationException invalid(String message) {
		return new ArchiveValidationException(ArchiveValidationException.Category.INVALID_ARCHIVE, message);
	}

}
