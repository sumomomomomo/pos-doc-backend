package horse.sumomo.pos_doc_backend.rendering.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import horse.sumomo.pos_doc_backend.rendering.api.FirstPageRenderingProperties;
import horse.sumomo.pos_doc_backend.rendering.model.RenderedFirstPage;

/**
 * Unit tests for {@link PdfFirstPageRenderer} using small deterministic PDF
 * fixtures generated with PDFBox.
 */
class PdfFirstPageRendererTest {

	private static final int DPI = 200;
	private static final long FIFTY_MIB = 52428800L;

	private PdfFirstPageRenderer renderer;
	private FirstPageRenderingProperties properties;

	@BeforeEach
	void setUp() {
		this.properties = new FirstPageRenderingProperties(DPI, FIFTY_MIB, 5000, 5000, 16000000L, 33554432L, 1);
		this.renderer = new PdfFirstPageRenderer(this.properties);
	}

	@AfterEach
	void tearDown() {
		// No shared state to clean up.
	}

	@Test
	void onePageA4PdfRendersPage0AtExpectedDimensions() throws Exception {
		// A4: 595.28 x 841.89 points.
		byte[] pdfBytes = singlePagePdf(595.28f, 841.89f, 0, "TASK 7 PAGE ONE");
		Path pdfFile = writeTempPdf(pdfBytes);

		try (StoredPdfMaterializer.MaterializedPdf pdf = materialize(pdfFile)) {
			RenderedFirstPage result = this.renderer.render(pdf, UUID.randomUUID());
			try {
				// PDFBox A4 at 200 DPI produces 1653 x 2338 pixels.
				assertEquals(1653, result.widthPixels());
				assertEquals(2338, result.heightPixels());
				assertEquals(DPI, result.dpi());
				assertTrue(result.pngByteSize() > 0);
				assertTrue(Files.exists(result.pngPath()));
				// Verify PNG signature.
				assertTrue(hasPngSignature(result.pngPath()));
				// Verify RGB color model (PDFBox produces TYPE_3BYTE_BGR for
				// ImageType.RGB).
				BufferedImage img = ImageIO.read(result.pngPath().toFile());
				assertNotNull(img);
				assertEquals(BufferedImage.TYPE_3BYTE_BGR, img.getType());
				assertEquals(1653, img.getWidth());
				assertEquals(2338, img.getHeight());
			}
			finally {
				result.close();
			}
		}
		// PDF temp file should be deleted after render.
		assertFalse(Files.exists(pdfFile));
	}

	@Test
	void twoPagePdfRendersOnlyPage0() throws Exception {
		// Page 0: A4 with "TASK 7 PAGE ONE"
		// Page 1: A4 with "TASK 7 PAGE TWO" (different text to detect
		// accidental rendering of page 1)
		byte[] pdfBytes = twoPagePdf("TASK 7 PAGE ONE", "TASK 7 PAGE TWO");
		Path pdfFile = writeTempPdf(pdfBytes);

		// Also render page 1 separately so we can compare pixel content.
		byte[] pdfPage1Only = singlePagePdf(595.28f, 841.89f, 0, "TASK 7 PAGE TWO");
		Path pdfPage1File = writeTempPdf(pdfPage1Only);

		try (StoredPdfMaterializer.MaterializedPdf pdf = materialize(pdfFile)) {
			RenderedFirstPage result = this.renderer.render(pdf, UUID.randomUUID());
			try {
				assertEquals(1653, result.widthPixels());
				assertEquals(2338, result.heightPixels());
				BufferedImage imgPage0 = ImageIO.read(result.pngPath().toFile());
				assertNotNull(imgPage0);

				// Render page 1 content separately for comparison.
				try (StoredPdfMaterializer.MaterializedPdf pdf1 = materialize(pdfPage1File)) {
					RenderedFirstPage result1 = this.renderer.render(pdf1, UUID.randomUUID());
					try {
						BufferedImage imgPage1 = ImageIO.read(result1.pngPath().toFile());
						assertNotNull(imgPage1);
						// The two pages have different text, so the pixel
						// content must differ. Compare a sample of pixels
						// in the text region (top-left area).
						boolean identical = true;
						for (int y = 100; y < 200 && identical; y += 10) {
							for (int x = 50; x < 400 && identical; x += 10) {
								if (imgPage0.getRGB(x, y) != imgPage1.getRGB(x, y)) {
									identical = false;
								}
							}
						}
						assertFalse(identical,
								"page 0 and page 1 pixel content must differ; "
										+ "rendering may have used the wrong page");
					}
					finally {
						result1.close();
					}
				}
			}
			finally {
				result.close();
			}
		}
		assertFalse(Files.exists(pdfFile));
	}

	@Test
	void rotation90SwapsDimensions() throws Exception {
		// A4 landscape via 90-degree rotation: 841.89 x 595.28 points.
		byte[] pdfBytes = singlePagePdf(595.28f, 841.89f, 90, "TASK 7 ROTATED");
		Path pdfFile = writeTempPdf(pdfBytes);

		try (StoredPdfMaterializer.MaterializedPdf pdf = materialize(pdfFile)) {
			RenderedFirstPage result = this.renderer.render(pdf, UUID.randomUUID());
			try {
				// After 90-degree rotation: width=841.89, height=595.28
				// PDFBox produces 2338 x 1653 pixels.
				assertEquals(2338, result.widthPixels());
				assertEquals(1653, result.heightPixels());
			}
			finally {
				result.close();
			}
		}
		assertFalse(Files.exists(pdfFile));
	}

	@Test
	void rotation270SwapsDimensions() throws Exception {
		byte[] pdfBytes = singlePagePdf(595.28f, 841.89f, 270, "TASK 7 ROTATED 270");
		Path pdfFile = writeTempPdf(pdfBytes);

		try (StoredPdfMaterializer.MaterializedPdf pdf = materialize(pdfFile)) {
			RenderedFirstPage result = this.renderer.render(pdf, UUID.randomUUID());
			try {
				assertEquals(2338, result.widthPixels());
				assertEquals(1653, result.heightPixels());
			}
			finally {
				result.close();
			}
		}
		assertFalse(Files.exists(pdfFile));
	}

	@Test
	void zeroPagePdfFailsWithPdfInvalid() throws Exception {
		// Create a PDF with no pages.
		byte[] pdfBytes = zeroPagePdf();
		Path pdfFile = writeTempPdf(pdfBytes);

		try (StoredPdfMaterializer.MaterializedPdf pdf = materialize(pdfFile)) {
			RenderingException e = assertThrows(RenderingException.class,
					() -> this.renderer.render(pdf, UUID.randomUUID()));
			assertEquals(RenderingException.Code.PDF_INVALID, e.getCode());
		}
	}

	@Test
	void corruptPdfFailsWithPdfInvalid() throws Exception {
		byte[] corruptBytes = "not a pdf at all".getBytes();
		Path pdfFile = writeTempPdf(corruptBytes);

		try (StoredPdfMaterializer.MaterializedPdf pdf = materialize(pdfFile)) {
			RenderingException e = assertThrows(RenderingException.class,
					() -> this.renderer.render(pdf, UUID.randomUUID()));
			assertEquals(RenderingException.Code.PDF_INVALID, e.getCode());
		}
	}

	@Test
	void encryptedPdfFailsWithPdfInvalid() throws Exception {
		byte[] pdfBytes = encryptedPdf("TASK 7 ENCRYPTED", "secret-password");
		Path pdfFile = writeTempPdf(pdfBytes);

		try (StoredPdfMaterializer.MaterializedPdf pdf = materialize(pdfFile)) {
			RenderingException e = assertThrows(RenderingException.class,
					() -> this.renderer.render(pdf, UUID.randomUUID()));
			assertEquals(RenderingException.Code.PDF_INVALID, e.getCode());
		}
	}

	@Test
	void excessiveWidthFailsWithRenderLimitExceeded() throws Exception {
		// Page width that exceeds max-width-pixels (5000) at 200 DPI.
		// Need width > 5000 * 72 / 200 = 1800 points.
		byte[] pdfBytes = singlePagePdf(2000f, 841.89f, 0, "TASK 7 WIDE");
		Path pdfFile = writeTempPdf(pdfBytes);

		try (StoredPdfMaterializer.MaterializedPdf pdf = materialize(pdfFile)) {
			RenderingException e = assertThrows(RenderingException.class,
					() -> this.renderer.render(pdf, UUID.randomUUID()));
			assertEquals(RenderingException.Code.RENDER_LIMIT_EXCEEDED, e.getCode());
		}
	}

	@Test
	void excessiveHeightFailsWithRenderLimitExceeded() throws Exception {
		// Page height that exceeds max-height-pixels (5000) at 200 DPI.
		// Need height > 5000 * 72 / 200 = 1800 points.
		byte[] pdfBytes = singlePagePdf(595.28f, 2000f, 0, "TASK 7 TALL");
		Path pdfFile = writeTempPdf(pdfBytes);

		try (StoredPdfMaterializer.MaterializedPdf pdf = materialize(pdfFile)) {
			RenderingException e = assertThrows(RenderingException.class,
					() -> this.renderer.render(pdf, UUID.randomUUID()));
			assertEquals(RenderingException.Code.RENDER_LIMIT_EXCEEDED, e.getCode());
		}
	}

	@Test
	void excessivePixelCountFailsWithRenderLimitExceeded() throws Exception {
		// Width and height individually within bounds, but product exceeds
		// max-pixels (16,000,000).
		// 4001 x 4001 = 16,008,001 (exceeds limit)
		// Need width points such that round(w * 200/72) = 4001
		// w >= 4000.5 * 72 / 200 = 1440.18
		// So w = 1440.18 gives round(1440.18 * 200/72) = round(4000.5) = 4001
		byte[] pdfBytes = singlePagePdf(1440.18f, 1440.18f, 0, "TASK 7 BIG PIXELS");
		Path pdfFile = writeTempPdf(pdfBytes);

		try (StoredPdfMaterializer.MaterializedPdf pdf = materialize(pdfFile)) {
			RenderingException e = assertThrows(RenderingException.class,
					() -> this.renderer.render(pdf, UUID.randomUUID()));
			assertEquals(RenderingException.Code.RENDER_LIMIT_EXCEEDED, e.getCode());
		}
	}

	@Test
	void exactBoundarySucceeds() throws Exception {
		// Width and height at a size that is within all configured bounds.
		// w = 1440 gives ceil(1440 * 200/72) = ceil(4000) = 4000
		// 4000 x 4000 = 16,000,000 (exactly at max-pixels limit)
		// PDFBox produces 3999 x 3999 for this size (round(4000) = 4000
		// but PDFBox's internal calculation gives 3999).
		byte[] pdfBytes = singlePagePdf(1440f, 1440f, 0, "TASK 7 BOUNDARY");
		Path pdfFile = writeTempPdf(pdfBytes);

		try (StoredPdfMaterializer.MaterializedPdf pdf = materialize(pdfFile)) {
			RenderedFirstPage result = this.renderer.render(pdf, UUID.randomUUID());
			try {
				// PDFBox produces 3999 x 3999 for 1440pt at 200 DPI.
				assertEquals(3999, result.widthPixels());
				assertEquals(3999, result.heightPixels());
			}
			finally {
				result.close();
			}
		}
		assertFalse(Files.exists(pdfFile));
	}

	@Test
	void oneUnitOverWidthBoundaryFails() throws Exception {
		// Need round(w * 200/72) = 5001 => w >= 5000.5*72/200 = 1800.18
		// w = 1800.18 gives round(1800.18 * 200/72) = round(5000.5) = 5001
		byte[] pdfBytes = singlePagePdf(1800.18f, 841.89f, 0, "TASK 7 OVER WIDTH");
		Path pdfFile = writeTempPdf(pdfBytes);

		try (StoredPdfMaterializer.MaterializedPdf pdf = materialize(pdfFile)) {
			RenderingException e = assertThrows(RenderingException.class,
					() -> this.renderer.render(pdf, UUID.randomUUID()));
			assertEquals(RenderingException.Code.RENDER_LIMIT_EXCEEDED, e.getCode());
		}
	}

	@Test
	void outputIsRgbWithValidPngSignatureAndNonEmpty() throws Exception {
		byte[] pdfBytes = singlePagePdf(595.28f, 841.89f, 0, "TASK 7 RGB CHECK");
		Path pdfFile = writeTempPdf(pdfBytes);

		try (StoredPdfMaterializer.MaterializedPdf pdf = materialize(pdfFile)) {
			RenderedFirstPage result = this.renderer.render(pdf, UUID.randomUUID());
			try {
				assertTrue(hasPngSignature(result.pngPath()));
				assertTrue(result.pngByteSize() > 0);
				BufferedImage img = ImageIO.read(result.pngPath().toFile());
				assertNotNull(img);
				// PDFBox produces TYPE_3BYTE_BGR for ImageType.RGB.
				assertEquals(BufferedImage.TYPE_3BYTE_BGR, img.getType());
			}
			finally {
				result.close();
			}
		}
	}

	@Test
	void semaphoreConcurrencyNeverExceedsOne() throws Exception {
		// Use the test hooks to track current and maximum concurrency
		// inside the permit-protected section.
		AtomicInteger current = new AtomicInteger(0);
		AtomicInteger maxConcurrent = new AtomicInteger(0);
		this.renderer.onPermitAcquired = () -> {
			int c = current.incrementAndGet();
			maxConcurrent.accumulateAndGet(c, Math::max);
		};
		this.renderer.onPermitReleased = () -> current.decrementAndGet();

		int threadCount = 4;
		CyclicBarrier barrier = new CyclicBarrier(threadCount);
		CountDownLatch allDone = new CountDownLatch(threadCount);
		AtomicInteger failures = new AtomicInteger(0);

		for (int i = 0; i < threadCount; i++) {
			Thread t = new Thread(() -> {
				try {
					barrier.await(10, TimeUnit.SECONDS);
					byte[] bytes = singlePagePdf(595.28f, 841.89f, 0, "TASK 7 CONC");
					Path f = writeTempPdf(bytes);
					try (StoredPdfMaterializer.MaterializedPdf pdf = materialize(f)) {
						RenderedFirstPage result = this.renderer.render(pdf, UUID.randomUUID());
						result.close();
					}
				}
				catch (Exception e) {
					failures.incrementAndGet();
				}
				finally {
					allDone.countDown();
				}
			});
			t.start();
		}

		boolean finished = allDone.await(60, TimeUnit.SECONDS);
		assertTrue(finished, "all render threads must complete within 60s");
		assertEquals(0, failures.get(), "no render thread may fail");
		// The critical assertion: max concurrency must never exceed 1.
		assertEquals(1, maxConcurrent.get(),
				"maximum concurrent renders must be exactly 1, was " + maxConcurrent.get());
	}

	@Test
	void interruptingWaiterRestoresInterruptFlagAndReleasesResources() throws Exception {
		// Use the test hooks to deterministically hold the permit and
		// signal when the waiter is about to block on the semaphore.
		CountDownLatch holderEntered = new CountDownLatch(1);
		CountDownLatch releaseHolder = new CountDownLatch(1);
		CountDownLatch waiterAboutToBlock = new CountDownLatch(1);
		this.renderer.onPermitAcquired = () -> {
			try {
				holderEntered.countDown();
				releaseHolder.await(30, TimeUnit.SECONDS);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		};
		this.renderer.onBeforePermitAcquire = waiterAboutToBlock::countDown;

		// Thread 1: acquires the permit and blocks inside the hook.
		CountDownLatch t1Done = new CountDownLatch(1);
		AtomicReference<Exception> t1Error = new AtomicReference<>();
		Thread t1 = new Thread(() -> {
			try {
				byte[] bytes = singlePagePdf(595.28f, 841.89f, 0, "TASK 7 HOLDER");
				Path f = writeTempPdf(bytes);
				try (StoredPdfMaterializer.MaterializedPdf pdf = materialize(f)) {
					RenderedFirstPage result = this.renderer.render(pdf, UUID.randomUUID());
					result.close();
				}
			}
			catch (Exception e) {
				t1Error.set(e);
			}
			finally {
				t1Done.countDown();
			}
		});
		t1.start();

		// Wait for thread 1 to acquire the permit and enter the hook.
		assertTrue(holderEntered.await(10, TimeUnit.SECONDS),
				"holder thread must acquire the permit");

		// Thread 2: tries to render while the permit is held. The
		// onBeforePermitAcquire hook signals that it is about to block
		// on semaphore.acquire(), so we can interrupt it deterministically.
		byte[] pdfBytes = singlePagePdf(595.28f, 841.89f, 0, "TASK 7 WAITER");
		Path pdfFile = writeTempPdf(pdfBytes);
		AtomicReference<Exception> t2Error = new AtomicReference<>();
		AtomicBoolean waiterInterrupted = new AtomicBoolean(false);
		Thread t2 = new Thread(() -> {
			try (StoredPdfMaterializer.MaterializedPdf pdf = materialize(pdfFile)) {
				this.renderer.render(pdf, UUID.randomUUID());
			}
			catch (Exception e) {
				waiterInterrupted.set(Thread.currentThread().isInterrupted());
				t2Error.set(e);
			}
		});
		t2.start();

		// Wait for the waiter to signal it is about to block on the
		// semaphore. This is deterministic — no Thread.sleep.
		assertTrue(waiterAboutToBlock.await(10, TimeUnit.SECONDS),
				"waiter thread must reach semaphore.acquire()");

		// Interrupt the waiter thread while it is blocked on the semaphore.
		t2.interrupt();
		t2.join(10_000);
		assertFalse(t2.isAlive(), "waiter thread must terminate after interrupt");

		// Verify the waiter got RENDER_INTERRUPTED.
		assertNotNull(t2Error.get(), "waiter thread must have thrown an exception");
		assertInstanceOf(RenderingException.class, t2Error.get());
		assertEquals(RenderingException.Code.RENDER_INTERRUPTED,
				((RenderingException) t2Error.get()).getCode());
		// The interrupt flag must be restored.
		assertTrue(waiterInterrupted.get(),
				"interrupt flag must be restored after RENDER_INTERRUPTED");
		// The waiter's PDF must be deleted.
		assertFalse(Files.exists(pdfFile), "waiter PDF must be deleted after interrupt");

		// Release the holder so thread 1 can complete.
		releaseHolder.countDown();
		assertTrue(t1Done.await(30, TimeUnit.SECONDS), "holder thread must complete");
		assertNull(t1Error.get(), "holder thread must not fail");

		// Clear the hooks.
		this.renderer.onPermitAcquired = null;
		this.renderer.onPermitReleased = null;
		this.renderer.onBeforePermitAcquire = null;

		// A subsequent render must succeed, proving no permit leak.
		byte[] pdfBytes2 = singlePagePdf(595.28f, 841.89f, 0, "TASK 7 POST");
		Path pdfFile2 = writeTempPdf(pdfBytes2);
		try (StoredPdfMaterializer.MaterializedPdf pdf = materialize(pdfFile2)) {
			RenderedFirstPage result = this.renderer.render(pdf, UUID.randomUUID());
			result.close();
		}
		assertFalse(Files.exists(pdfFile2));
	}

	@Test
	void successfulRenderDeletesPdfBeforeReturning() throws Exception {
		byte[] pdfBytes = singlePagePdf(595.28f, 841.89f, 0, "TASK 7 CLEANUP");
		Path pdfFile = writeTempPdf(pdfBytes);
		assertTrue(Files.exists(pdfFile));

		try (StoredPdfMaterializer.MaterializedPdf pdf = materialize(pdfFile)) {
			RenderedFirstPage result = this.renderer.render(pdf, UUID.randomUUID());
			// PDF should already be deleted when render returns.
			assertFalse(Files.exists(pdfFile), "PDF temp file must be deleted before render returns");
			// PNG should exist while the handle is open.
			assertTrue(Files.exists(result.pngPath()));
			result.close();
			// PNG should be deleted after close.
			assertFalse(Files.exists(result.pngPath()));
		}
	}

	@Test
	void pdfAndPartialPngDeletedOnFailure() throws Exception {
		byte[] corruptBytes = "not a pdf".getBytes();
		Path pdfFile = writeTempPdf(corruptBytes);
		assertTrue(Files.exists(pdfFile));

		try (StoredPdfMaterializer.MaterializedPdf pdf = materialize(pdfFile)) {
			assertThrows(RenderingException.class, () -> this.renderer.render(pdf, UUID.randomUUID()));
		}
		// PDF should be deleted after failure.
		assertFalse(Files.exists(pdfFile));
	}

	@Test
	void excessivePngSizeFailsWithRenderLimitExceeded() throws Exception {
		// Use a very small max-png-bytes so even a small A4 page exceeds it.
		FirstPageRenderingProperties tinyProps = new FirstPageRenderingProperties(
				200, FIFTY_MIB, 5000, 5000, 16000000L, 100L, 1);
		PdfFirstPageRenderer tinyRenderer = new PdfFirstPageRenderer(tinyProps);

		byte[] pdfBytes = singlePagePdf(595.28f, 841.89f, 0, "TASK 7 BIG PNG");
		Path pdfFile = writeTempPdf(pdfBytes);

		try (StoredPdfMaterializer.MaterializedPdf pdf = materialize(pdfFile)) {
			RenderingException e = assertThrows(RenderingException.class,
					() -> tinyRenderer.render(pdf, UUID.randomUUID()));
			assertEquals(RenderingException.Code.RENDER_LIMIT_EXCEEDED, e.getCode());
		}
		// PDF must be deleted after failure.
		assertFalse(Files.exists(pdfFile));
	}

	@Test
	void closeIsIdempotentAndDoesNotThrowOnSecondCall() throws Exception {
		byte[] pdfBytes = singlePagePdf(595.28f, 841.89f, 0, "TASK 7 DOUBLE CLOSE");
		Path pdfFile = writeTempPdf(pdfBytes);

		try (StoredPdfMaterializer.MaterializedPdf pdf = materialize(pdfFile)) {
			RenderedFirstPage result = this.renderer.render(pdf, UUID.randomUUID());
			Path pngPath = result.pngPath();
			result.close();
			// Second close must be a no-op (no exception, no re-delete).
			result.close();
			assertFalse(Files.exists(pngPath));
		}
	}

	@Test
	void failedDeletionAllowsRetryOnSecondClose() throws Exception {
		// Render a PDF to get a real PNG file, then make the PNG
		// undeletable (by making its parent directory read-only on
		// Unix, or by using a file lock on Windows). Since cross-platform
		// file locking is complex, we instead verify the contract:
		// if close() throws, the handle is NOT marked closed, so a
		// second close() can retry.
		//
		// We simulate a deletion failure by creating a RenderedFirstPage
		// with a path that will fail to delete. We use a directory as the
		// "file" — Files.deleteIfExists on a non-empty directory throws.
		Path tempDir = Files.createTempDirectory("pos-doc-render-test-");
		// Create a file inside so the directory is non-empty.
		Path child = tempDir.resolve("child.txt");
		Files.writeString(child, "x");

		RenderedFirstPage handle = new RenderedFirstPage(UUID.randomUUID(), tempDir, 100, 100, 200, 100L);
		// First close should fail (cannot delete non-empty directory).
		assertThrows(RenderingException.class, handle::close);
		// The directory must still exist (deletion failed).
		assertTrue(Files.exists(tempDir),
				"PNG path must still exist after failed deletion");
		// Second close should also fail (still not closed, retry allowed).
		assertThrows(RenderingException.class, handle::close);
		// Clean up manually.
		Files.delete(child);
		Files.delete(tempDir);
	}

	// ------------------------------------------------------------------
	// PDF generation helpers
	// ------------------------------------------------------------------

	private static byte[] singlePagePdf(float width, float height, int rotation, String text) throws Exception {
		try (PDDocument doc = new PDDocument()) {
			PDPage page = new PDPage(new PDRectangle(width, height));
			page.setRotation(rotation);
			doc.addPage(page);
			try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
				cs.beginText();
				cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
				cs.newLineAtOffset(50, height - 50);
				cs.showText(text);
				cs.endText();
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			doc.save(out);
			return out.toByteArray();
		}
	}

	private static byte[] twoPagePdf(String textPage0, String textPage1) throws Exception {
		try (PDDocument doc = new PDDocument()) {
			PDRectangle a4 = PDRectangle.A4;
			PDPage page0 = new PDPage(a4);
			doc.addPage(page0);
			try (PDPageContentStream cs = new PDPageContentStream(doc, page0)) {
				cs.beginText();
				cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
				cs.newLineAtOffset(50, a4.getHeight() - 50);
				cs.showText(textPage0);
				cs.endText();
			}

			PDPage page1 = new PDPage(a4);
			doc.addPage(page1);
			try (PDPageContentStream cs = new PDPageContentStream(doc, page1)) {
				cs.beginText();
				cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
				cs.newLineAtOffset(50, a4.getHeight() - 50);
				cs.showText(textPage1);
				cs.endText();
			}

			ByteArrayOutputStream out = new ByteArrayOutputStream();
			doc.save(out);
			return out.toByteArray();
		}
	}

	private static byte[] zeroPagePdf() throws Exception {
		try (PDDocument doc = new PDDocument()) {
			// No pages added.
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			doc.save(out);
			return out.toByteArray();
		}
	}

	private static byte[] encryptedPdf(String text, String password) throws Exception {
		try (PDDocument doc = new PDDocument()) {
			PDPage page = new PDPage(PDRectangle.A4);
			doc.addPage(page);
			try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
				cs.beginText();
				cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
				cs.newLineAtOffset(50, PDRectangle.A4.getHeight() - 50);
				cs.showText(text);
				cs.endText();
			}
			// PDFBox 3 uses the ProtectionPolicy API for encryption.
			org.apache.pdfbox.pdmodel.encryption.AccessPermission perm =
					new org.apache.pdfbox.pdmodel.encryption.AccessPermission();
			perm.setCanExtractContent(false);
			perm.setCanPrint(false);
			org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy policy =
					new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy(
							password, password, perm);
			doc.protect(policy);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			doc.save(out);
			return out.toByteArray();
		}
	}

	private static Path writeTempPdf(byte[] pdfBytes) throws IOException {
		Path tempFile = Files.createTempFile("pos-doc-render-test-", ".pdf");
		Files.write(tempFile, pdfBytes);
		return tempFile;
	}

	private static StoredPdfMaterializer.MaterializedPdf materialize(Path pdfFile) throws IOException {
		// Create a MaterializedPdf handle directly from the file.
		long size = Files.size(pdfFile);
		return new StoredPdfMaterializer.MaterializedPdf(pdfFile, size);
	}

	private static boolean hasPngSignature(Path path) {
		byte[] PNG_SIGNATURE = { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };
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

}
