package horse.sumomo.pos_doc_backend.rendering.application;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import horse.sumomo.pos_doc_backend.rendering.model.DocumentRenderSource;
import horse.sumomo.pos_doc_backend.rendering.model.RenderedFirstPage;
import horse.sumomo.pos_doc_backend.rendering.service.PdfFirstPageRenderer;
import horse.sumomo.pos_doc_backend.rendering.service.RenderingException;
import horse.sumomo.pos_doc_backend.rendering.service.StoredPdfMaterializer;

/**
 * Orchestrates the lookup, materialization, and rendering of one document's
 * first page.
 *
 * <p>This service is <em>not</em> annotated with {@code @Transactional}. The
 * metadata lookup runs in its own short read-only transaction (inside
 * {@link DocumentRenderSourceService#load(UUID)}); the MinIO download and
 * PDFBox rendering happen outside any transaction.
 *
 * <p>The orchestration uses nested try-with-resources so that:
 * <ul>
 *   <li>A metadata failure creates no temp file.</li>
 *   <li>A download failure leaves no temp file.</li>
 *   <li>A PDFBox failure deletes both the PDF and any partial PNG.</li>
 *   <li>A successful return has already deleted the PDF and leaves only the
 *       PNG owned by the returned handle.</li>
 *   <li>Closing the returned handle leaves neither file.</li>
 * </ul>
 */
@Service
public class FirstPageRenderPreparationService {

	private static final Logger log = LoggerFactory.getLogger(FirstPageRenderPreparationService.class);

	private final DocumentRenderSourceService sourceService;
	private final StoredPdfMaterializer materializer;
	private final PdfFirstPageRenderer renderer;

	public FirstPageRenderPreparationService(DocumentRenderSourceService sourceService,
			StoredPdfMaterializer materializer, PdfFirstPageRenderer renderer) {
		this.sourceService = sourceService;
		this.materializer = materializer;
		this.renderer = renderer;
	}

	/**
	 * Prepares the first-page PNG for one document.
	 *
	 * @param documentId the document UUID
	 * @return a {@link RenderedFirstPage} handle owning the PNG temp file
	 * @throws RenderingException with a stable code on any failure
	 */
	public RenderedFirstPage prepare(UUID documentId) {
		// 1. Short read-only transaction: load and validate metadata.
		//    A failure here creates no temp file.
		DocumentRenderSource source = this.sourceService.load(documentId);

		// 2. Bounded MinIO download to a unique temp file.
		//    A failure here leaves no temp file (the materializer deletes
		//    the partial file on every exception).
		try (StoredPdfMaterializer.MaterializedPdf pdf = this.materializer.materialize(source)) {
			// 3. Render page 0 to a bounded PNG.
			//    The renderer deletes the PDF temp file before returning.
			//    A PDFBox failure deletes both the PDF and any partial PNG.
			RenderedFirstPage result = this.renderer.render(pdf, documentId);
			log.debug("First page prepared; documentId={}", documentId);
			return result;
		}
		// If the renderer throws, the materialized PDF is closed (deleted)
		// by this try-with-resources, and the renderer has already deleted
		// any partial PNG.
	}

}
