package horse.sumomo.pos_doc_backend.controller;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import com.yourcompany.pos.api.PosRecordsApi;
import com.yourcompany.pos.api.model.PosDocument;
import com.yourcompany.pos.api.model.PosRecord;
import com.yourcompany.pos.api.model.PosRecordPatch;
import com.yourcompany.pos.api.model.PosRecordSearchPage;
import com.yourcompany.pos.api.model.PosRecordSearchRequest;
import com.yourcompany.pos.api.model.UploadAccepted;
import com.yourcompany.pos.api.model.VerifyPosRecordRequest;

import horse.sumomo.pos_doc_backend.content.ContentDescriptor;
import horse.sumomo.pos_doc_backend.content.DocumentContentException;
import horse.sumomo.pos_doc_backend.content.DocumentContentService;
import horse.sumomo.pos_doc_backend.ingestion.application.PosArchiveIntakeService;
import horse.sumomo.pos_doc_backend.ingestion.application.PosDocumentListService;
import horse.sumomo.pos_doc_backend.ingestion.application.UploadResult;
import horse.sumomo.pos_doc_backend.ingestion.mapping.PosDocumentApiMapper;
import horse.sumomo.pos_doc_backend.review.PosRecordCommandService;
import horse.sumomo.pos_doc_backend.review.PosRecordReadService;
import horse.sumomo.pos_doc_backend.review.PosRecordSearchService;

import jakarta.servlet.http.HttpServletResponse;

/**
 * POS records endpoints.
 *
 * <p>{@code uploadPosRecord} delegates to {@link PosArchiveIntakeService} and
 * returns {@code 202}. The remaining operations are persistence-backed:
 * search, detail read, metadata PATCH, verification, and soft delete are owned
 * by the {@code review} application services; this controller only maps the
 * generated interface onto them.
 */
@RestController
public class PosRecordsController implements PosRecordsApi {

	private static final Logger log = LoggerFactory.getLogger(PosRecordsController.class);

	private final PosArchiveIntakeService intakeService;
	private final PosDocumentListService documentListService;
	private final PosRecordReadService readService;
	private final PosRecordSearchService searchService;
	private final PosRecordCommandService commandService;
	private final DocumentContentService contentService;

	/**
	 * Servlet context path, used to build the external {@code Location}
	 * header for the upload response.
	 */
	private final String contextPath;

	public PosRecordsController(PosArchiveIntakeService intakeService,
			PosDocumentListService documentListService,
			PosRecordReadService readService,
			PosRecordSearchService searchService,
			PosRecordCommandService commandService,
			DocumentContentService contentService,
			@Value("${server.servlet.context-path:/api/v1}") String contextPath) {
		this.intakeService = intakeService;
		this.documentListService = documentListService;
		this.readService = readService;
		this.searchService = searchService;
		this.commandService = commandService;
		this.contentService = contentService;
		this.contextPath = contextPath;
	}

	@Override
	public ResponseEntity<UploadAccepted> uploadPosRecord(MultipartFile file, String policyNumber) {
		UploadResult result = this.intakeService.intake(file, policyNumber);
		UploadAccepted accepted = new UploadAccepted(result.posRecordId(), result.jobId(),
				UploadAccepted.StatusEnum.UPLOADED);
		log.debug("Upload accepted: posRecordId={}, jobId={}", result.posRecordId(), result.jobId());
		return ResponseEntity
				.accepted()
				.location(URI.create(contextPath + "/pos-records/" + result.posRecordId()))
				.body(accepted);
	}

	@Override
	public ResponseEntity<PosRecordSearchPage> searchPosRecords(PosRecordSearchRequest posRecordSearchRequest) {
		return ResponseEntity.ok(this.searchService.search(posRecordSearchRequest));
	}

	@Override
	public ResponseEntity<PosRecord> getPosRecord(UUID posRecordId) {
		return ResponseEntity.ok(this.readService.getRecord(posRecordId));
	}

	@Override
	public ResponseEntity<PosRecord> updatePosRecord(UUID posRecordId, PosRecordPatch posRecordPatch) {
		return ResponseEntity.ok(this.commandService.patch(posRecordId, posRecordPatch));
	}

	@Override
	public ResponseEntity<PosRecord> verifyPosRecord(UUID posRecordId, VerifyPosRecordRequest verifyPosRecordRequest) {
		return ResponseEntity.ok(this.commandService.verify(posRecordId, verifyPosRecordRequest));
	}

	@Override
	public ResponseEntity<Void> deletePosRecord(UUID posRecordId) {
		this.commandService.delete(posRecordId);
		return ResponseEntity.noContent().build();
	}

	@Override
	public ResponseEntity<List<PosDocument>> listPosDocuments(UUID posRecordId) {
		List<PosDocument> docs = this.documentListService.listDocuments(posRecordId).stream()
				.map(PosDocumentApiMapper::toDto).toList();
		return ResponseEntity.ok(docs);
	}

	@Override
	public ResponseEntity<Resource> getPosDocumentContent(UUID posRecordId, UUID documentId) {
		ContentDescriptor descriptor = this.contentService.pdfDescriptor(posRecordId, documentId);
		return streamBinary(descriptor, true);
	}

	@Override
	public ResponseEntity<Resource> getPosSourceArchiveContent(UUID posRecordId) {
		ContentDescriptor descriptor = this.contentService.sourceArchiveDescriptor(posRecordId);
		return streamBinary(descriptor, false);
	}

	/**
	 * Writes the protected binary body directly to the servlet response with the
	 * required security headers, then returns an empty {@code ResponseEntity} (the
	 * body is already written). The object-store stream is closed by the content
	 * service; failures before the response commits are mapped to sanitized
	 * problems by the exception handler.
	 */
	private ResponseEntity<Resource> streamBinary(ContentDescriptor descriptor, boolean inline) {
		HttpServletResponse response = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes())
				.getResponse();
		response.setContentType(descriptor.contentType());
		response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
				contentDisposition(descriptor.originalFilename(), inline));
		response.setContentLengthLong(descriptor.expectedByteSize());
		response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate");
		response.setHeader("Pragma", "no-cache");
		response.setHeader("X-Content-Type-Options", "nosniff");
		try {
			this.contentService.streamContent(descriptor, response.getOutputStream());
		}
		catch (IOException e) {
			throw new DocumentContentException(DocumentContentException.Code.DOCUMENT_STORAGE_UNAVAILABLE, e);
		}
		return ResponseEntity.ok().build();
	}

	private static String contentDisposition(String originalFilename, boolean inline) {
		ContentDisposition.Builder builder = inline ? ContentDisposition.inline() : ContentDisposition.attachment();
		return builder.filename(sanitizeFilename(originalFilename), StandardCharsets.UTF_8).build().toString();
	}

	/**
	 * Sanitizes a filename for a {@code Content-Disposition} header: strips CR, LF,
	 * and NUL and neutralizes path separators so the raw name can never inject
	 * header lines or traversal.
	 */
	private static String sanitizeFilename(String raw) {
		if (raw == null || raw.isBlank()) {
			return "download";
		}
		String cleaned = raw.replace("\r", "").replace("\n", "").replace("\u0000", "")
				.replace("/", "_").replace("\\", "_").trim();
		return cleaned.isEmpty() ? "download" : cleaned;
	}

}
