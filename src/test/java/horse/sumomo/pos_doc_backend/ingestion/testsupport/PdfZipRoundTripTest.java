package horse.sumomo.pos_doc_backend.ingestion.testsupport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

/**
 * Diagnostic test: verifies that PDFs created by {@link SyntheticPdfFactory}
 * survive a ZIP round-trip and can be parsed by PDFBox.
 */
class PdfZipRoundTripTest {

	@Test
	void pdfSurvivesZipRoundTripAndParses() throws Exception {
		byte[] pdfBytes = SyntheticPdfFactory.createPdf("Hello World");
		System.out.println("PDF size: " + pdfBytes.length + " bytes");
		System.out.println("PDF header: " + new String(pdfBytes, 0, Math.min(20, pdfBytes.length)));

		// Verify the PDF can be parsed directly.
		try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
			System.out.println("Direct parse OK. Pages: " + doc.getNumberOfPages());
		}

		// Store in a ZIP.
		ByteArrayOutputStream zipOut = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(zipOut)) {
			ZipEntry entry = new ZipEntry("test.pdf");
			zip.putNextEntry(entry);
			zip.write(pdfBytes);
			zip.closeEntry();
		}
		byte[] zipBytes = zipOut.toByteArray();
		System.out.println("ZIP size: " + zipBytes.length + " bytes");

		// Extract from the ZIP.
		Path zipFile = Files.createTempFile("test", ".zip");
		Files.write(zipFile, zipBytes);
		byte[] extracted;
		try (ZipFile zf = new ZipFile(zipFile.toFile())) {
			ZipEntry ze = zf.getEntry("test.pdf");
			try (InputStream in = zf.getInputStream(ze)) {
				extracted = in.readAllBytes();
			}
		}
		zipFile.toFile().delete();

		System.out.println("Extracted size: " + extracted.length + " bytes");
		assertArrayEquals(pdfBytes, extracted, "PDF bytes must survive ZIP round-trip");

		// Write to a temp file and parse with PDFBox (same as PdfFirstPageRenderer).
		Path pdfFile = Files.createTempFile("test", ".pdf");
		Files.write(pdfFile, extracted);
		try (PDDocument doc = Loader.loadPDF(pdfFile.toFile())) {
			System.out.println("File parse OK. Pages: " + doc.getNumberOfPages());
			assertEquals(1, doc.getNumberOfPages());
		}
		finally {
			pdfFile.toFile().delete();
		}

		assertTrue(extracted.length > 0);
	}

}
