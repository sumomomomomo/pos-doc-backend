package horse.sumomo.pos_doc_backend.ocr.application;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import horse.sumomo.pos_doc_backend.ocr.client.LlamaCppOcrClient;
import horse.sumomo.pos_doc_backend.ocr.model.OcrResult;
import horse.sumomo.pos_doc_backend.rendering.application.FirstPageRenderPreparationService;
import horse.sumomo.pos_doc_backend.rendering.model.RenderedFirstPage;

/**
 * Composes Task 7 first-page rendering with the llama.cpp OCR client.
 *
 * <p>This service is <em>not</em> annotated with {@code @Transactional}.
 * The rendering preparation runs in its own short read-only transaction
 * (inside {@link FirstPageRenderPreparationService#prepare(UUID)}); the OCR
 * HTTP call happens outside any transaction.
 *
 * <p>The returned {@link RenderedFirstPage} handle is closed via
 * try-with-resources on every success and failure path, so the temporary
 * PNG is deleted before {@link #recognize(UUID)} returns or throws.
 */
public final class FirstPageOcrService {

	private static final Logger log = LoggerFactory.getLogger(FirstPageOcrService.class);

	private final FirstPageRenderPreparationService renderPreparationService;
	private final LlamaCppOcrClient ocrClient;

	public FirstPageOcrService(FirstPageRenderPreparationService renderPreparationService,
			LlamaCppOcrClient ocrClient) {
		this.renderPreparationService = renderPreparationService;
		this.ocrClient = ocrClient;
	}

	/**
	 * Renders the first page of the given document and sends it to the
	 * llama.cpp OCR service.
	 *
	 * @param documentId the document UUID; must not be null
	 * @return an immutable, ephemeral {@link OcrResult}
	 * @throws horse.sumomo.pos_doc_backend.rendering.service.RenderingException
	 *             with a stable code on rendering failure
	 * @throws horse.sumomo.pos_doc_backend.ocr.service.OcrException with a
	 *             stable code on OCR failure
	 */
	public OcrResult recognize(UUID documentId) {
		if (documentId == null) {
			throw new IllegalArgumentException("documentId must not be null");
		}

		try (RenderedFirstPage page = this.renderPreparationService.prepare(documentId)) {
			OcrResult result = this.ocrClient.recognize(page);
			log.debug("OCR completed; documentId={}", documentId);
			return result;
		}
		// If OCR fails, the PNG is deleted by try-with-resources.
		// If both OCR and PNG cleanup fail, the OCR failure remains primary
		// and the sanitized cleanup exception is suppressed.
	}

}
