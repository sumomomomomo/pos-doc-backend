package horse.sumomo.pos_doc_backend.service;

import com.yourcompany.pos.api.model.DocumentProcessingStatus;
import com.yourcompany.pos.api.model.DocumentType;
import com.yourcompany.pos.api.model.PosDocument;
import com.yourcompany.pos.api.model.PosRecord;
import com.yourcompany.pos.api.model.PosRecordSearchPage;
import com.yourcompany.pos.api.model.PosRecordStatus;
import com.yourcompany.pos.api.model.PosRecordSummary;
import com.yourcompany.pos.api.model.StorageObjectSummary;
import com.yourcompany.pos.api.model.UploadAccepted;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Deterministic dummy data for the POS-record endpoints. Skeleton only: no
 * persistence, storage, or processing is performed.
 */
@Service
public class DummyPosRecordService {

    public static final UUID POS_RECORD_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID DOCUMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    public static final UUID STORAGE_OBJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    public static final OffsetDateTime FIXED_TIMESTAMP = OffsetDateTime.parse("2026-01-01T00:00:00Z");

    private static final String SHA_256 = "0000000000000000000000000000000000000000000000000000000000000000";
    private static final String EREF_NUMBER = "EREF-DUMMY-001";
    private static final String POLICY_NUMBER = "POLICY-DUMMY-001";
    private static final String POLICYHOLDER_NAME = "Dummy Policyholder";
    private static final String CONSULTANT_NAME = "Dummy Consultant";
    private static final LocalDate POLICY_CREATE_DATE = LocalDate.parse("2026-01-01");
    private static final String UPLOADER_SUBJECT = "dummy-google-subject";
    private static final String ARCHIVE_FILENAME = "dummy-pos-archive.zip";
    private static final String ARCHIVE_CONTENT_TYPE = "application/zip";
    private static final long ARCHIVE_BYTE_SIZE = 128L;
    private static final String PDF_FILENAME = "dummy-document.pdf";
    private static final String PDF_CONTENT_TYPE = "application/pdf";
    private static final long PDF_BYTE_SIZE = 64L;

    /**
     * Dummy upload acceptance response using the fixed identifiers.
     */
    public UploadAccepted uploadAccepted() {
        return new UploadAccepted(POS_RECORD_ID, DummyIngestionJobService.INGESTION_JOB_ID,
                UploadAccepted.StatusEnum.UPLOADED);
    }

    /**
     * Dummy search page containing exactly one fixed POS record summary.
     */
    public PosRecordSearchPage searchPage() {
        PosRecordSummary summary = new PosRecordSummary(POS_RECORD_ID, PosRecordStatus.COMPLETED,
                FIXED_TIMESTAMP, FIXED_TIMESTAMP)
                .erefNumber(EREF_NUMBER)
                .policyNumber(POLICY_NUMBER)
                .policyholderName(POLICYHOLDER_NAME)
                .consultantName(CONSULTANT_NAME)
                .policyCreateDate(POLICY_CREATE_DATE);
        return new PosRecordSearchPage(List.of(summary), 0, 20, 1L, 1);
    }

    /**
     * Complete dummy POS record whose ID equals the supplied path ID.
     */
    public PosRecord posRecord(UUID id) {
        return new PosRecord(id, PosRecordStatus.COMPLETED, sourceArchive(), FIXED_TIMESTAMP,
                FIXED_TIMESTAMP, UPLOADER_SUBJECT, 1L)
                .erefNumber(EREF_NUMBER)
                .policyNumber(POLICY_NUMBER)
                .policyholderName(POLICYHOLDER_NAME)
                .consultantName(CONSULTANT_NAME)
                .policyCreateDate(POLICY_CREATE_DATE);
    }

    /**
     * Dummy PDF document associated with the supplied POS record ID.
     */
    public PosDocument document(UUID posRecordId) {
        return new PosDocument(DOCUMENT_ID, posRecordId, pdfStorageObject(),
                DocumentType.FA_PRUPLANNER_REPORT, DocumentProcessingStatus.COMPLETED);
    }

    private StorageObjectSummary sourceArchive() {
        return new StorageObjectSummary(STORAGE_OBJECT_ID, ARCHIVE_FILENAME, ARCHIVE_CONTENT_TYPE,
                ARCHIVE_BYTE_SIZE, SHA_256);
    }

    private StorageObjectSummary pdfStorageObject() {
        return new StorageObjectSummary(STORAGE_OBJECT_ID, PDF_FILENAME, PDF_CONTENT_TYPE, PDF_BYTE_SIZE,
                SHA_256);
    }
}
