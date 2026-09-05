package horse.sumomo.pos_doc_backend.ocr.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import horse.sumomo.pos_doc_backend.ocr.client.LlamaCppOcrClient;
import horse.sumomo.pos_doc_backend.ocr.model.OcrResult;
import horse.sumomo.pos_doc_backend.ocr.service.OcrException;
import horse.sumomo.pos_doc_backend.rendering.application.FirstPageRenderPreparationService;
import horse.sumomo.pos_doc_backend.rendering.model.RenderedFirstPage;
import horse.sumomo.pos_doc_backend.rendering.service.RenderingException;

/**
 * Orchestration tests for {@link FirstPageOcrService}.
 *
 * <p>Mocks only {@link FirstPageRenderPreparationService} and
 * {@link LlamaCppOcrClient}. Uses a real temporary PNG and real
 * {@link RenderedFirstPage} handle.
 */
class FirstPageOcrServiceTest {

	private static final String SYNTHETIC_OCR_TEXT = "synthetic-ocr-text-for-testing";
	private static final String MODEL = "/models/dotsmocr-1.8b-q8_0.gguf";

	@TempDir
	Path tempDir;

	@Test
	void successCallsPrepareAndRecognizeOnceAndDeletesPng() throws Exception {
		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = this.tempDir.resolve("success.png");
		Files.write(pngPath, pngBytes);

		UUID documentId = UUID.randomUUID();
		RenderedFirstPage page = new RenderedFirstPage(documentId, pngPath, 100, 100, 200, pngBytes.length);
		OcrResult expectedResult = new OcrResult(documentId, SYNTHETIC_OCR_TEXT, MODEL, "stop", 1);

		FirstPageRenderPreparationService renderService = mock(FirstPageRenderPreparationService.class);
		when(renderService.prepare(documentId)).thenReturn(page);

		LlamaCppOcrClient ocrClient = mock(LlamaCppOcrClient.class);
		when(ocrClient.recognize(page)).thenReturn(expectedResult);

		FirstPageOcrService service = new FirstPageOcrService(renderService, ocrClient);
		OcrResult result = service.recognize(documentId);

		assertSame(expectedResult, result);
		assertEquals(SYNTHETIC_OCR_TEXT, result.text());
		assertEquals(MODEL, result.model());
		assertEquals("stop", result.finishReason());
		assertEquals(1, result.promptVersion());

		// Verify prepare and recognize were each called exactly once.
		verify(renderService, times(1)).prepare(documentId);
		verify(ocrClient, times(1)).recognize(page);

		// Verify the PNG was deleted before return.
		assertFalse(Files.exists(pngPath), "PNG should be deleted after recognize returns");
	}

	@Test
	void ocrFailureDeletesPng() throws Exception {
		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = this.tempDir.resolve("failure.png");
		Files.write(pngPath, pngBytes);

		UUID documentId = UUID.randomUUID();
		RenderedFirstPage page = new RenderedFirstPage(documentId, pngPath, 100, 100, 200, pngBytes.length);

		FirstPageRenderPreparationService renderService = mock(FirstPageRenderPreparationService.class);
		when(renderService.prepare(documentId)).thenReturn(page);

		LlamaCppOcrClient ocrClient = mock(LlamaCppOcrClient.class);
		when(ocrClient.recognize(page)).thenThrow(new OcrException(OcrException.Code.OCR_SERVICE_UNAVAILABLE));

		FirstPageOcrService service = new FirstPageOcrService(renderService, ocrClient);
		OcrException e = assertThrows(OcrException.class, () -> service.recognize(documentId));
		assertEquals(OcrException.Code.OCR_SERVICE_UNAVAILABLE, e.getCode());

		// Verify the PNG was deleted.
		assertFalse(Files.exists(pngPath), "PNG should be deleted after OCR failure");
	}

	@Test
	void renderingFailureDoesNotCallOcrClient() throws Exception {
		UUID documentId = UUID.randomUUID();

		FirstPageRenderPreparationService renderService = mock(FirstPageRenderPreparationService.class);
		when(renderService.prepare(documentId))
				.thenThrow(new RenderingException(RenderingException.Code.DOCUMENT_NOT_FOUND));

		LlamaCppOcrClient ocrClient = mock(LlamaCppOcrClient.class);

		FirstPageOcrService service = new FirstPageOcrService(renderService, ocrClient);
		RenderingException e = assertThrows(RenderingException.class, () -> service.recognize(documentId));
		assertEquals(RenderingException.Code.DOCUMENT_NOT_FOUND, e.getCode());

		// Verify the OCR client was never called.
		verify(ocrClient, never()).recognize(any());
	}

	@Test
	void renderingExceptionIsPropagatedUnchanged() throws Exception {
		UUID documentId = UUID.randomUUID();
		RenderingException original = new RenderingException(RenderingException.Code.PDF_INVALID);

		FirstPageRenderPreparationService renderService = mock(FirstPageRenderPreparationService.class);
		when(renderService.prepare(documentId)).thenThrow(original);

		LlamaCppOcrClient ocrClient = mock(LlamaCppOcrClient.class);

		FirstPageOcrService service = new FirstPageOcrService(renderService, ocrClient);
		RenderingException e = assertThrows(RenderingException.class, () -> service.recognize(documentId));
		assertSame(original, e);
	}

	@Test
	void ocrExceptionIsPropagatedUnchanged() throws Exception {
		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = this.tempDir.resolve("propagated.png");
		Files.write(pngPath, pngBytes);

		UUID documentId = UUID.randomUUID();
		RenderedFirstPage page = new RenderedFirstPage(documentId, pngPath, 100, 100, 200, pngBytes.length);
		OcrException original = new OcrException(OcrException.Code.OCR_TIMEOUT);

		FirstPageRenderPreparationService renderService = mock(FirstPageRenderPreparationService.class);
		when(renderService.prepare(documentId)).thenReturn(page);

		LlamaCppOcrClient ocrClient = mock(LlamaCppOcrClient.class);
		when(ocrClient.recognize(page)).thenThrow(original);

		FirstPageOcrService service = new FirstPageOcrService(renderService, ocrClient);
		OcrException e = assertThrows(OcrException.class, () -> service.recognize(documentId));
		assertSame(original, e);
	}

	@Test
	void noTransactionIsActiveDuringModelCall() throws Exception {
		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = this.tempDir.resolve("no-tx.png");
		Files.write(pngPath, pngBytes);

		UUID documentId = UUID.randomUUID();
		RenderedFirstPage page = new RenderedFirstPage(documentId, pngPath, 100, 100, 200, pngBytes.length);
		OcrResult expectedResult = new OcrResult(documentId, SYNTHETIC_OCR_TEXT, MODEL, "stop", 1);

		FirstPageRenderPreparationService renderService = mock(FirstPageRenderPreparationService.class);
		when(renderService.prepare(documentId)).thenReturn(page);

		LlamaCppOcrClient ocrClient = mock(LlamaCppOcrClient.class);
		when(ocrClient.recognize(page)).thenAnswer(invocation -> {
			// Verify no transaction is active during the model call.
			assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
					"No transaction should be active during the OCR model call");
			return expectedResult;
		});

		FirstPageOcrService service = new FirstPageOcrService(renderService, ocrClient);
		OcrResult result = service.recognize(documentId);

		assertNotNull(result);
		assertEquals(SYNTHETIC_OCR_TEXT, result.text());
	}

	@Test
	void nullDocumentIdIsRejected() {
		FirstPageRenderPreparationService renderService = mock(FirstPageRenderPreparationService.class);
		LlamaCppOcrClient ocrClient = mock(LlamaCppOcrClient.class);

		FirstPageOcrService service = new FirstPageOcrService(renderService, ocrClient);
		assertThrows(IllegalArgumentException.class, () -> service.recognize(null));
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	private static byte[] createSyntheticPng(int payloadLength) {
		byte[] png = new byte[8 + payloadLength];
		png[0] = (byte) 0x89;
		png[1] = 0x50;
		png[2] = 0x4E;
		png[3] = 0x47;
		png[4] = 0x0D;
		png[5] = 0x0A;
		png[6] = 0x1A;
		png[7] = 0x0A;
		for (int i = 8; i < png.length; i++) {
			png[i] = (byte) (i % 256);
		}
		return png;
	}

}
