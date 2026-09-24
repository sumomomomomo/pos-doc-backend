package horse.sumomo.pos_doc_backend.ingestion.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;

import horse.sumomo.pos_doc_backend.ingestion.api.UploadLimitsProperties;
import horse.sumomo.pos_doc_backend.ingestion.archive.PdfEntryDecoder;
import horse.sumomo.pos_doc_backend.ingestion.archive.ZipArchiveValidator;
import horse.sumomo.pos_doc_backend.ingestion.consumer.ArchiveExtractionService.ExtractedPdf;
import horse.sumomo.pos_doc_backend.infrastructure.minio.MinioObjectStorage;

/**
 * Proves that {@link ArchiveExtractionService} streams each entry against the
 * <em>remaining</em> effective raw-byte allowance (per-entry cap / remaining
 * total-uncompressed / remaining compression-ratio) — not just the static
 * {@code maxEntryBytes} — so the cumulative limits are enforced during
 * streaming, mirroring the validator's pre-entry arithmetic.
 *
 * <p>A {@link RecordingPdfEntryDecoder} captures the exact
 * {@code maxSourceBytes} the service passes to every {@code decode()} call.
 */
class ArchiveExtractionServiceEffectiveAllowanceTest {

	/** Records the {@code maxSourceBytes} the service passes to every decode. */
	static final class RecordingPdfEntryDecoder extends PdfEntryDecoder {
		private final List<Long> allowances = new ArrayList<>();

		List<Long> allowances() {
			return List.copyOf(this.allowances);
		}

		@Override
		public PdfEntryDecoder.DecodeResult decode(InputStream in, OutputStream out, long maxSourceBytes)
				throws IOException {
			this.allowances.add(maxSourceBytes);
			return super.decode(in, out, maxSourceBytes);
		}
	}

	@Test
	void laterEntryIsDecodedAgainstTheRemainingTotalAllowanceNotMaxEntryBytes() throws Exception {
		byte[] pdf1 = rawPdf(80);
		byte[] pdf2 = rawPdf(50);
		long maxUncompressed = 300L;
		long maxEntry = 300L; // == maxUncompressed (the maximum allowed)
		UploadLimitsProperties limits = new UploadLimitsProperties(10485760L, maxUncompressed, maxEntry, 100, 100);

		RecordingPdfEntryDecoder decoder = new RecordingPdfEntryDecoder();
		ArchiveExtractionService service = newService(limits, decoder);

		Map<String, byte[]> entries = new LinkedHashMap<>();
		entries.put("first.pdf", pdf1);
		entries.put("second.pdf", pdf2);
		Path zip = writeZip(zipBytes(entries));
		List<ExtractedPdf> result = service.extractAndStore(zip, Files.size(zip), UUID.randomUUID());
		assertEquals(2, result.size());

		List<Long> allowances = decoder.allowances();
		assertEquals(maxEntry, allowances.get(0), "first entry allowance should be the full budget");
		assertEquals(maxUncompressed - pdf1.length, allowances.get(1),
				"second entry allowance should be the remaining total budget");
		assertTrue(allowances.get(1) < maxEntry, "second entry allowance must be strictly below maxEntryBytes");
	}

	@Test
	void laterEntryIsDecodedAgainstTheRemainingRatioAllowanceNotMaxEntryBytes() throws Exception {
		byte[] pdf1 = rawPdf(20);
		byte[] pdf2 = rawPdf(20);
		long maxUncompressed = 100000L;
		long maxEntry = 100000L;
		// Ratio of 1 makes the remaining-ratio budget (sourceByteCount -
		// cumulative) far smaller than the generous total/per-entry budgets.
		UploadLimitsProperties limits = new UploadLimitsProperties(10485760L, maxUncompressed, maxEntry, 100, 1);

		RecordingPdfEntryDecoder decoder = new RecordingPdfEntryDecoder();
		ArchiveExtractionService service = newService(limits, decoder);

		Map<String, byte[]> entries = new LinkedHashMap<>();
		entries.put("first.pdf", pdf1);
		entries.put("second.pdf", pdf2);
		byte[] zipBytes = zipBytes(entries);
		Path zip = writeZip(zipBytes);
		long sourceByteCount = zipBytes.length;
		List<ExtractedPdf> result = service.extractAndStore(zip, sourceByteCount, UUID.randomUUID());
		assertEquals(2, result.size());

		List<Long> allowances = decoder.allowances();
		assertEquals(sourceByteCount - pdf1.length, allowances.get(1),
				"second entry allowance should be the remaining ratio budget");
		assertTrue(allowances.get(1) < maxEntry, "second entry allowance must be strictly below maxEntryBytes");
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	private static ArchiveExtractionService newService(UploadLimitsProperties limits, PdfEntryDecoder decoder) {
		MinioObjectStorage storage = mock(MinioObjectStorage.class);
		when(storage.exists(anyString())).thenReturn(false);
		return new ArchiveExtractionService(new ZipArchiveValidator(limits), storage, limits, decoder);
	}

	/** A raw "PDF" (leading {@code %PDF-}) of exactly {@code size} bytes. */
	private static byte[] rawPdf(int size) {
		byte[] result = new byte[size];
		System.arraycopy("%PDF-".getBytes(), 0, result, 0, 5);
		for (int i = 5; i < size; i++) {
			result[i] = (byte) 'a';
		}
		return result;
	}

	private static Path writeZip(byte[] zipBytes) throws Exception {
		Path zip = Files.createTempFile("extraction-allowance-", ".zip");
		zip.toFile().deleteOnExit();
		Files.write(zip, zipBytes);
		return zip;
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

}
