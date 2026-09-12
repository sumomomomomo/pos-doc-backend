package horse.sumomo.pos_doc_backend.controller;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.yourcompany.pos.api.PosRecordsApi;
import com.yourcompany.pos.api.model.PosDocument;
import com.yourcompany.pos.api.model.PosRecord;
import com.yourcompany.pos.api.model.PosRecordPatch;
import com.yourcompany.pos.api.model.PosRecordSearchPage;
import com.yourcompany.pos.api.model.PosRecordSearchRequest;
import com.yourcompany.pos.api.model.UploadAccepted;
import com.yourcompany.pos.api.model.VerifyPosRecordRequest;

import horse.sumomo.pos_doc_backend.ingestion.application.PosArchiveIntakeService;
import horse.sumomo.pos_doc_backend.ingestion.application.PosDocumentListService;
import horse.sumomo.pos_doc_backend.ingestion.application.UploadResult;
import horse.sumomo.pos_doc_backend.ingestion.mapping.PosDocumentApiMapper;
import horse.sumomo.pos_doc_backend.review.PosRecordCommandService;
import horse.sumomo.pos_doc_backend.review.PosRecordReadService;
import horse.sumomo.pos_doc_backend.review.PosRecordSearchService;

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
			@Value("${server.servlet.context-path:/api/v1}") String contextPath) {
		this.intakeService = intakeService;
		this.documentListService = documentListService;
		this.readService = readService;
		this.searchService = searchService;
		this.commandService = commandService;
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

}
