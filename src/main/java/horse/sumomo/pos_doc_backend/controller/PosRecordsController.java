package horse.sumomo.pos_doc_backend.controller;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

import horse.sumomo.pos_doc_backend.ingestion.application.PosArchiveIntakeService;
import horse.sumomo.pos_doc_backend.ingestion.application.UploadResult;
import horse.sumomo.pos_doc_backend.service.DummyPosRecordService;

/**
 * POS records endpoints.
 *
 * <p>{@link #uploadPosRecord} is real: it delegates to
 * {@link PosArchiveIntakeService} and returns {@code 202} with the persisted
 * identifiers. The remaining operations keep their Task 1 dummy behavior;
 * they are not yet persistence-backed.
 *
 * <p>TODO Task 6+: back {@code getPosRecord}, {@code searchPosRecords},
 * {@code updatePosRecord}, {@code deletePosRecord}, and
 * {@code listPosDocuments} with persistence; only the upload is real in
 * this task.
 */
@RestController
public class PosRecordsController implements PosRecordsApi {

	private static final Logger log = LoggerFactory.getLogger(PosRecordsController.class);

	private final PosArchiveIntakeService intakeService;
	private final DummyPosRecordService posRecordService;

	/**
	 * Servlet context path, used to build the external {@code Location}
	 * header for the upload response.
	 */
	private final String contextPath;

	public PosRecordsController(PosArchiveIntakeService intakeService,
			DummyPosRecordService posRecordService,
			@org.springframework.beans.factory.annotation.Value("${server.servlet.context-path:/api/v1}") String contextPath) {
		this.intakeService = intakeService;
		this.posRecordService = posRecordService;
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
		return ResponseEntity.ok(posRecordService.searchPage());
	}

	@Override
	public ResponseEntity<PosRecord> getPosRecord(UUID posRecordId) {
		return ResponseEntity.ok(posRecordService.posRecord(posRecordId));
	}

	@Override
	public ResponseEntity<PosRecord> updatePosRecord(UUID posRecordId, PosRecordPatch posRecordPatch) {
		return ResponseEntity.ok(posRecordService.posRecord(posRecordId));
	}

	@Override
	public ResponseEntity<Void> deletePosRecord(UUID posRecordId) {
		return ResponseEntity.noContent().build();
	}

	@Override
	public ResponseEntity<List<PosDocument>> listPosDocuments(UUID posRecordId) {
		return ResponseEntity.ok(List.of(posRecordService.document(posRecordId)));
	}

}
