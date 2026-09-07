package horse.sumomo.pos_doc_backend.ingestion.testsupport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Calendar;
import java.util.TimeZone;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/**
 * Creates minimal, valid synthetic PDFs using PDFBox for integration tests.
 *
 * <p>The generated PDFs are small, contain a single A4 page with a short
 * text string, and are valid enough for PDFBox to render the first page to
 * a PNG. No real insurance documents or real PII.
 */
public final class SyntheticPdfFactory {

	private SyntheticPdfFactory() {
	}

	/**
	 * Creates a minimal valid PDF with a single A4 page containing the
	 * given text.
	 *
	 * @param text the text to render on the page
	 * @return the PDF bytes
	 */
	public static byte[] createPdf(String text) {
		try (PDDocument document = new PDDocument();
				ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			// Set a fixed creation/modification date so the PDF bytes are
			// deterministic across calls. PDFBox includes the current
			// timestamp in the document info by default, which would make
			// the PDF non-deterministic.
			Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
			cal.clear();
			cal.set(2026, Calendar.JANUARY, 1, 0, 0, 0);
			document.getDocumentInformation().setCreationDate(cal);
			document.getDocumentInformation().setModificationDate(cal);

			PDRectangle a4 = PDRectangle.A4;
			PDPage page = new PDPage(a4);
			document.addPage(page);
			try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
				contentStream.beginText();
				contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
				contentStream.newLineAtOffset(50, a4.getHeight() - 50);
				contentStream.showText(text);
				contentStream.endText();
			}
			document.save(out);
			return out.toByteArray();
		}
		catch (IOException e) {
			throw new AssertionError("Failed to create synthetic PDF", e);
		}
	}

}
