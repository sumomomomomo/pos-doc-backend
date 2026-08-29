package horse.sumomo.pos_doc_backend.controller;

import com.yourcompany.pos.api.PosRecordsApi;
import com.yourcompany.pos.api.model.PosDocument;
import com.yourcompany.pos.api.model.PosRecord;
import com.yourcompany.pos.api.model.PosRecordPatch;
import com.yourcompany.pos.api.model.PosRecordSearchPage;
import com.yourcompany.pos.api.model.PosRecordSearchRequest;
import com.yourcompany.pos.api.model.UploadAccepted;
import horse.sumomo.pos_doc_backend.service.DummyPosRecordService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * HTTP skeleton for the POS records endpoints. Returns deterministic dummy
 * data only; no persistence, storage, or processing is performed.
 */
@RestController
public class PosRecordsController implements PosRecordsApi {

    private final DummyPosRecordService posRecordService;

    /**
     * Servlet context path, used to build the external {@code Location}
     * header for the upload response.
     */
    private final String contextPath;

    public PosRecordsController(DummyPosRecordService posRecordService,
            @Value("${server.servlet.context-path:/api/v1}") String contextPath) {
        this.posRecordService = posRecordService;
        this.contextPath = contextPath;
    }

    @Override
    public ResponseEntity<UploadAccepted> uploadPosRecord(MultipartFile file, String policyNumber) {
        UploadAccepted accepted = posRecordService.uploadAccepted();
        return ResponseEntity
                .accepted()
                .location(java.net.URI.create(contextPath + "/pos-records/" + DummyPosRecordService.POS_RECORD_ID))
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
