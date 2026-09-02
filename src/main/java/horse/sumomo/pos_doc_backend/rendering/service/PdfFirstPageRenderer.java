package horse.sumomo.pos_doc_backend.rendering.service;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Semaphore;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import horse.sumomo.pos_doc_backend.rendering.api.FirstPageRenderingProperties;
import horse.sumomo.pos_doc_backend.rendering.model.RenderedFirstPage;

/**
 * Renders page index {@code 0} of one materialized PDF to a bounded RGB PNG
 * using Apache PDFBox.
 *
 * <p>Rendering concurrency is limited to one inside this application
 * instance by a fair semaphore with exactly one permit. The PDF is loaded
 * through PDFBox's file-backed {@link Loader} API. Page dimensions are
 * validated before any raster allocation. The PNG is written to a unique
 * PII-free temporary file through a {@link BoundedOutputStream} capped at
 * {@code max-png-bytes}.
 *
 * <p>The input PDF temp file is deleted as soon as the PDFBox document has
 * closed and rendering no longer needs it. The returned
 * {@link RenderedFirstPage} handle owns only the PNG temp file.
 */
@Component
public class PdfFirstPageRenderer {

	private static final Logger log = LoggerFactory.getLogger(PdfFirstPageRenderer.class);

	private static final String PNG_TEMP_PREFIX = "pos-doc-render-png-";
	private static final String PNG_TEMP_SUFFIX = ".png.part";
	private static final byte[] PNG_SIGNATURE = { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };

	private final FirstPageRenderingProperties properties;
	private final Semaphore renderPermit;

	/**
	 * Optional test hook invoked after the permit is acquired and before
	 * {@link #doRender} is called, and again after the permit is released.
	 * Package-private; null in production.
	 */
	Runnable onPermitAcquired;
	Runnable onPermitReleased;

	/**
	 * Optional test hook invoked just before {@code renderPermit.acquire()}
	 * is called. Package-private; null in production. Allows tests to
	 * deterministically signal that a thread is about to block on the
	 * semaphore.
	 */
	Runnable onBeforePermitAcquire;

	public PdfFirstPageRenderer(FirstPageRenderingProperties properties) {
		this.properties = properties;
		this.renderPermit = new Semaphore(properties.maxConcurrentRenders(), true);
	}

	/**
	 * Renders page index 0 of the materialized PDF.
	 *
	 * @param pdf        the materialized PDF (temp file + byte count)
	 * @param documentId the document UUID
	 * @return a {@link RenderedFirstPage} handle owning the PNG temp file
	 * @throws RenderingException with a stable code on any failure
	 */
	public RenderedFirstPage render(StoredPdfMaterializer.MaterializedPdf pdf, UUID documentId) {
		// Acquire the fair semaphore. If interrupted while waiting, restore
		// the interrupt flag, close/delete the materialized PDF, and throw
		// the sanitized RENDER_INTERRUPTED failure.
		boolean permitAcquired = false;
		try {
			if (this.onBeforePermitAcquire != null) {
				this.onBeforePermitAcquire.run();
			}
			this.renderPermit.acquire();
			permitAcquired = true;
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			pdf.close();
			throw new RenderingException(RenderingException.Code.RENDER_INTERRUPTED, e);
		}

		try {
			if (this.onPermitAcquired != null) {
				this.onPermitAcquired.run();
			}
			return doRender(pdf, documentId);
		}
		finally {
			if (permitAcquired) {
				if (this.onPermitReleased != null) {
					this.onPermitReleased.run();
				}
				this.renderPermit.release();
			}
		}
	}

	private RenderedFirstPage doRender(StoredPdfMaterializer.MaterializedPdf pdf, UUID documentId) {
		Path pdfPath = pdf.getTempPath();
		PDDocument document = null;
		Path pngPath = null;
		try {
			// Load the local PDF through PDFBox using a file-backed API.
			try {
				document = Loader.loadPDF(pdfPath.toFile());
			}
			catch (IOException e) {
				throw new RenderingException(RenderingException.Code.PDF_INVALID, e);
			}

			// Reject encrypted/password-protected PDFs before rendering.
			if (document.isEncrypted()) {
				throw new RenderingException(RenderingException.Code.PDF_INVALID);
			}

			// Require at least one page.
			if (document.getNumberOfPages() < 1) {
				throw new RenderingException(RenderingException.Code.PDF_INVALID);
			}

			// Inspect page 0 before calling a rasterizing method.
			PDPage page = document.getPage(0);
			PDRectangle cropBox = page.getCropBox();
			float widthPoints = cropBox.getWidth();
			float heightPoints = cropBox.getHeight();
			int rotation = normalizeRotation(page.getRotation());

			// Calculate the effective output dimensions using the page crop
			// box, rotation, and configured DPI. Use Math.ceil for the
			// pre-allocation check so a dimension slightly over a configured
			// boundary is caught before PDFBox allocates the image.
			long ceilWidth = (long) Math.ceil(widthPoints * this.properties.dpi() / 72.0);
			long ceilHeight = (long) Math.ceil(heightPoints * this.properties.dpi() / 72.0);
			if (ceilWidth > Integer.MAX_VALUE || ceilHeight > Integer.MAX_VALUE) {
				throw new RenderingException(RenderingException.Code.PAGE_DIMENSIONS_INVALID);
			}
			int pixelWidth = (int) ceilWidth;
			int pixelHeight = (int) ceilHeight;
			if (rotation == 90 || rotation == 270) {
				int tmp = pixelWidth;
				pixelWidth = pixelHeight;
				pixelHeight = tmp;
			}

			// Reject non-finite, zero, negative, or excessive page
			// dimensions.
			if (!Float.isFinite(widthPoints) || !Float.isFinite(heightPoints)
					|| widthPoints <= 0 || heightPoints <= 0) {
				throw new RenderingException(RenderingException.Code.PAGE_DIMENSIONS_INVALID);
			}
			if (pixelWidth <= 0 || pixelHeight <= 0) {
				throw new RenderingException(RenderingException.Code.PAGE_DIMENSIONS_INVALID);
			}
			if (pixelWidth > this.properties.maxWidthPixels()
					|| pixelHeight > this.properties.maxHeightPixels()) {
				throw new RenderingException(RenderingException.Code.RENDER_LIMIT_EXCEEDED);
			}
			// Require width * height <= max-pixels using safe long
			// arithmetic.
			long totalPixels = (long) pixelWidth * (long) pixelHeight;
			if (totalPixels > this.properties.maxPixels()) {
				throw new RenderingException(RenderingException.Code.RENDER_LIMIT_EXCEEDED);
			}

			// Render only page 0 with PDFRenderer.renderImageWithDPI.
			PDFRenderer renderer = new PDFRenderer(document);
			BufferedImage image;
			try {
				image = renderer.renderImageWithDPI(0, this.properties.dpi(), ImageType.RGB);
			}
			catch (IOException e) {
				throw new RenderingException(RenderingException.Code.RENDER_FAILED, e);
			}

			// Confirm the produced image dimensions are positive and still
			// within every configured bound.
			int imgWidth = image.getWidth();
			int imgHeight = image.getHeight();
			if (imgWidth <= 0 || imgHeight <= 0) {
				throw new RenderingException(RenderingException.Code.RENDER_LIMIT_EXCEEDED);
			}
			if (imgWidth > this.properties.maxWidthPixels() || imgHeight > this.properties.maxHeightPixels()) {
				throw new RenderingException(RenderingException.Code.RENDER_LIMIT_EXCEEDED);
			}
			long imgTotalPixels = (long) imgWidth * (long) imgHeight;
			if (imgTotalPixels > this.properties.maxPixels()) {
				throw new RenderingException(RenderingException.Code.RENDER_LIMIT_EXCEEDED);
			}

			// Write PNG to a unique PII-free temporary file through
			// BoundedOutputStream capped at max-png-bytes.
			try {
				pngPath = Files.createTempFile(PNG_TEMP_PREFIX, PNG_TEMP_SUFFIX);
			}
			catch (IOException e) {
				throw new RenderingException(RenderingException.Code.TEMP_STORAGE_UNAVAILABLE, e);
			}

			try (var fileOut = Files.newOutputStream(pngPath,
					java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
					var boundedOut = new BoundedOutputStream(fileOut, this.properties.maxPngBytes())) {
				boolean written;
				try {
					written = ImageIO.write(image, "png", boundedOut);
				}
				catch (IOException e) {
					// ImageIO rendering/encoding failure (e.g. unavailable
					// PNG writer, PDFBox/ImageIO internal error).
					deleteQuietly(pngPath);
					throw new RenderingException(RenderingException.Code.RENDER_FAILED, e);
				}
				if (!written) {
					deleteQuietly(pngPath);
					throw new RenderingException(RenderingException.Code.RENDER_FAILED);
				}
			}
			catch (IOException e) {
				// Local file I/O failure (disk full, filesystem error,
				// permission denied) — retryable storage problem.
				deleteQuietly(pngPath);
				throw new RenderingException(RenderingException.Code.TEMP_STORAGE_UNAVAILABLE, e);
			}

			// Verify the finished file begins with the eight-byte PNG
			// signature and its actual size is positive and no greater than
			// max-png-bytes.
			long actualSize;
			try {
				actualSize = Files.size(pngPath);
			}
			catch (IOException e) {
				// Local file I/O failure (disk full, filesystem error).
				deleteQuietly(pngPath);
				throw new RenderingException(RenderingException.Code.TEMP_STORAGE_UNAVAILABLE, e);
			}
			if (actualSize <= 0 || actualSize > this.properties.maxPngBytes()) {
				deleteQuietly(pngPath);
				throw new RenderingException(RenderingException.Code.RENDER_LIMIT_EXCEEDED);
			}
			if (!hasPngSignature(pngPath)) {
				deleteQuietly(pngPath);
				throw new RenderingException(RenderingException.Code.RENDER_FAILED);
			}

			// Release the image reference after PNG encoding.
			image = null;

			// Delete the input PDF temp file as soon as the PDFBox document
			// has closed and rendering no longer needs it.
			// (Document close happens in the finally block below; we delete
			// the PDF file now because rendering is complete.)
			deleteQuietly(pdfPath);

			log.debug("First page rendered; documentId={}, width={}, height={}, dpi={}, pngBytes={}",
					documentId, imgWidth, imgHeight, this.properties.dpi(), actualSize);
			return new RenderedFirstPage(documentId, pngPath, imgWidth, imgHeight, this.properties.dpi(),
					actualSize);
		}
		catch (RenderingException e) {
			// Clean up the PNG on any rendering failure.
			deleteQuietly(pngPath);
			// Delete the PDF if it still exists (e.g. PDFBox load failed).
			deleteQuietly(pdfPath);
			throw e;
		}
		catch (RuntimeException e) {
			deleteQuietly(pngPath);
			deleteQuietly(pdfPath);
			throw new RenderingException(RenderingException.Code.RENDER_FAILED, e);
		}
		finally {
			if (document != null) {
				try {
					document.close();
				}
				catch (IOException ignored) {
					// best effort
				}
			}
			// If the PDF file was not already deleted (e.g. an early
			// failure), delete it now.
			deleteQuietly(pdfPath);
		}
	}

	private static int normalizeRotation(int rotation) {
		int normalized = ((rotation % 360) + 360) % 360;
		if (normalized != 0 && normalized != 90 && normalized != 180 && normalized != 270) {
			throw new RenderingException(RenderingException.Code.PAGE_DIMENSIONS_INVALID);
		}
		return normalized;
	}

	private static boolean hasPngSignature(Path path) {
		try (InputStream in = Files.newInputStream(path)) {
			byte[] header = new byte[PNG_SIGNATURE.length];
			int read = 0;
			while (read < PNG_SIGNATURE.length) {
				int r = in.read(header, read, PNG_SIGNATURE.length - read);
				if (r == -1) {
					return false;
				}
				read += r;
			}
			for (int i = 0; i < PNG_SIGNATURE.length; i++) {
				if (header[i] != PNG_SIGNATURE[i]) {
					return false;
				}
			}
			return true;
		}
		catch (IOException e) {
			return false;
		}
	}

	private static void deleteQuietly(Path path) {
		if (path == null) {
			return;
		}
		try {
			Files.deleteIfExists(path);
		}
		catch (IOException ignored) {
			// best effort
		}
	}

}
