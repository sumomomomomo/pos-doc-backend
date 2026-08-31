package horse.sumomo.pos_doc_backend.controller;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import horse.sumomo.pos_doc_backend.ingestion.application.IntakeException;
import horse.sumomo.pos_doc_backend.ingestion.application.PosArchiveIntakeService;
import horse.sumomo.pos_doc_backend.ingestion.application.PosDocumentListService;
import horse.sumomo.pos_doc_backend.ingestion.application.UploadResult;
import horse.sumomo.pos_doc_backend.ingestion.archive.ArchiveValidationException;
import horse.sumomo.pos_doc_backend.persistence.entity.IngestionJobEntity;
import horse.sumomo.pos_doc_backend.persistence.entity.PosRecordEntity;
import horse.sumomo.pos_doc_backend.persistence.model.JobStatus;
import horse.sumomo.pos_doc_backend.persistence.repository.IngestionJobRepository;
import horse.sumomo.pos_doc_backend.service.DummyIngestionJobService;
import horse.sumomo.pos_doc_backend.service.DummyPosRecordService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-layer tests for the API. Loads only the two handwritten controllers.
 * The persistence-backed collaborators ({@link PosArchiveIntakeService} for
 * the real upload, {@link IngestionJobRepository} for the job lookup) are
 * mocked; the search/get/patch/delete/document operations still use the real
 * dummy services.
 *
 * <p>The generated interface mappings are relative to
 * {@code ${openapi.pOSDocumentIngestion.base-path}} (overridden to empty in
 * {@code application.yaml}) and the external {@code /api/v1} prefix is
 * supplied by the servlet context path. MockMvc does not apply the servlet
 * context path, so requests here use the bare mapped paths.
 */
@WebMvcTest(controllers = {PosRecordsController.class, IngestionJobsController.class})
@Import({DummyPosRecordService.class, DummyIngestionJobService.class})
class ApiSkeletonTest {

    private static final String FIXED_POS_RECORD_ID = "00000000-0000-0000-0000-000000000001";
    private static final String FIXED_JOB_ID = "00000000-0000-0000-0000-000000000004";
    private static final String FIXED_LOCATION = "/api/v1/pos-records/" + FIXED_POS_RECORD_ID;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PosArchiveIntakeService intakeService;

    @MockitoBean
    private PosDocumentListService documentListService;

    @MockitoBean
    private IngestionJobRepository ingestionJobRepository;

    @Test
    void uploadPosRecordReturns202WithPersistedIdsAndLocation() throws Exception {
        UUID posRecordId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        when(this.intakeService.intake(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new UploadResult(posRecordId, jobId));
        MockMultipartFile file = new MockMultipartFile("file", "dummy.zip", "application/zip",
                new byte[] {1, 2, 3, 4});

        mockMvc.perform(multipart("/pos-records")
                        .file(file)
                        .param("policyNumber", "POLICY-UPLOAD-001"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/v1/pos-records/" + posRecordId))
                .andExpect(jsonPath("$.posRecordId").value(posRecordId.toString()))
                .andExpect(jsonPath("$.jobId").value(jobId.toString()))
                .andExpect(jsonPath("$.status").value("UPLOADED"));
    }

    @Test
    void searchPosRecordsReturnsSingleDummyPage() throws Exception {
        mockMvc.perform(post("/pos-records/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(FIXED_POS_RECORD_ID))
                .andExpect(jsonPath("$.items[0].policyNumber").value("POLICY-DUMMY-001"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void getPosRecordEchoesPathIdWithDummyValues() throws Exception {
        String pathId = "11111111-1111-1111-1111-111111111111";

        mockMvc.perform(get("/pos-records/{id}", pathId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(pathId))
                .andExpect(jsonPath("$.erefNumber").value("EREF-DUMMY-001"))
                .andExpect(jsonPath("$.policyNumber").value("POLICY-DUMMY-001"))
                .andExpect(jsonPath("$.policyholderName").value("Dummy Policyholder"))
                .andExpect(jsonPath("$.consultantName").value("Dummy Consultant"))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void updatePosRecordEchoesPathIdWithVersionOne() throws Exception {
        String pathId = "11111111-1111-1111-1111-111111111111";

        mockMvc.perform(patch("/pos-records/{id}", pathId)
                        .contentType(MediaType.parseMediaType("application/merge-patch+json"))
                        .content("{\"expectedVersion\":1,\"policyNumber\":\"POLICY-PATCH-001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(pathId))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void deletePosRecordReturns204WithEmptyBody() throws Exception {
        String pathId = "11111111-1111-1111-1111-111111111111";

        mockMvc.perform(delete("/pos-records/{id}", pathId))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    void listPosDocumentsReturnsEmptyArrayWhenNoDocumentsExist() throws Exception {
        String pathId = "11111111-1111-1111-1111-111111111111";
        when(this.documentListService.listDocuments(org.mockito.ArgumentMatchers.any(UUID.class)))
                .thenReturn(java.util.List.of());

        mockMvc.perform(get("/pos-records/{id}/documents", pathId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void getIngestionJobReturnsPersistedJob() throws Exception {
        UUID jobId = UUID.randomUUID();
        UUID posRecordId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-01-02T03:04:05Z");
        IngestionJobEntity entity = org.mockito.Mockito.mock(IngestionJobEntity.class);
        PosRecordEntity posRecord = org.mockito.Mockito.mock(PosRecordEntity.class);
        when(posRecord.getId()).thenReturn(posRecordId);
        when(entity.getId()).thenReturn(jobId);
        when(entity.getPosRecord()).thenReturn(posRecord);
        when(entity.getStatus()).thenReturn(JobStatus.QUEUED);
        when(entity.getAttemptCount()).thenReturn(0L);
        when(entity.getCreatedAt()).thenReturn(createdAt);
        when(this.ingestionJobRepository.findByIdAndPosRecordDeletedAtIsNull(jobId))
                .thenReturn(Optional.of(entity));

        mockMvc.perform(get("/ingestion-jobs/{jobId}", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(jobId.toString()))
                .andExpect(jsonPath("$.posRecordId").value(posRecordId.toString()))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.attemptCount").value(0));
    }

    @Test
    void getIngestionJobReturns404ProblemWhenUnknown() throws Exception {
        UUID jobId = UUID.randomUUID();
        when(this.ingestionJobRepository.findByIdAndPosRecordDeletedAtIsNull(jobId))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/ingestion-jobs/{jobId}", jobId))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("INGESTION_JOB_NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("The requested resource does not exist."));
    }

    @Test
    void uploadWithoutFilePartReturns400() throws Exception {
        mockMvc.perform(multipart("/pos-records")
                        .param("policyNumber", "POLICY-UPLOAD-001"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void nonUuidPathParameterReturns400() throws Exception {
        mockMvc.perform(get("/pos-records/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------
    // upload failure contract (Task 4-5, step 23)
    // ------------------------------------------------------------------

    private MockMultipartFile zipFile(String filename, byte[] bytes) {
        return new MockMultipartFile("file", filename, "application/zip", bytes);
    }

    @Test
    void oversizeUploadReturns413ArchiveTooLarge() throws Exception {
        when(this.intakeService.intake(any(), any()))
                .thenThrow(new IntakeException(IntakeException.Code.ARCHIVE_TOO_LARGE));

        mockMvc.perform(multipart("/pos-records").file(zipFile("EREF-OVER.zip", new byte[] { 1 })))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(413))
                .andExpect(jsonPath("$.code").value("ARCHIVE_TOO_LARGE"));
    }

    @Test
    void unsupportedArchiveTypeReturns415() throws Exception {
        when(this.intakeService.intake(any(), any()))
                .thenThrow(new ArchiveValidationException(
                        ArchiveValidationException.Category.UNSUPPORTED_ARCHIVE_TYPE, "not a zip"));

        mockMvc.perform(multipart("/pos-records").file(zipFile("EREF-UNSUP.zip", new byte[] { 1 })))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_ARCHIVE_TYPE"));
    }

    @Test
    void invalidArchiveReturns422() throws Exception {
        when(this.intakeService.intake(any(), any()))
                .thenThrow(new ArchiveValidationException(
                        ArchiveValidationException.Category.INVALID_ARCHIVE, "bad archive"));

        mockMvc.perform(multipart("/pos-records").file(zipFile("EREF-BAD.zip", new byte[] { 1 })))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.code").value("INVALID_ARCHIVE"));
    }

    @Test
    void duplicateErefReturns409WithDistinctCode() throws Exception {
        when(this.intakeService.intake(any(), any()))
                .thenThrow(new IntakeException(IntakeException.Code.DUPLICATE_EREF_NUMBER));

        mockMvc.perform(multipart("/pos-records")
                        .file(zipFile("EREF-DUP.zip", new byte[] { 1 }))
                        .param("policyNumber", "POLICY-DUP-001"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("DUPLICATE_EREF_NUMBER"));
    }

    @Test
    void duplicatePolicyReturns409WithDistinctCode() throws Exception {
        when(this.intakeService.intake(any(), any()))
                .thenThrow(new IntakeException(IntakeException.Code.DUPLICATE_POLICY_NUMBER));

        mockMvc.perform(multipart("/pos-records")
                        .file(zipFile("EREF-DUPPOL.zip", new byte[] { 1 }))
                        .param("policyNumber", "POLICY-DUP-001"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("DUPLICATE_POLICY_NUMBER"));
    }

    @Test
    void internalErrorIsSanitized500AndLeaksNoSensitiveValues() throws Exception {
        // A raw database failure with PII-ish text must not leak into the
        // problem body; only the stable sanitized code/detail is returned.
        when(this.intakeService.intake(any(), any()))
                .thenThrow(new IntakeException(IntakeException.Code.INGESTION_INTAKE_FAILED,
                        new RuntimeException("sqlite: UNIQUE constraint failed: eref=EREF-SECRET-001, key=archives/x/y.zip")));

        mockMvc.perform(multipart("/pos-records")
                        .file(zipFile("EREF-SECRET-001.zip", new byte[] { 1 }))
                        .param("policyNumber", "POLICY-SECRET-001"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.code").value("INGESTION_INTAKE_FAILED"))
                // Sensitive values must be absent from the problem response.
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    org.junit.jupiter.api.Assertions.assertFalse(body.contains("EREF-SECRET-001"),
                            "problem body must not contain the eRef: " + body);
                    org.junit.jupiter.api.Assertions.assertFalse(body.contains("POLICY-SECRET-001"),
                            "problem body must not contain the policy number: " + body);
                    org.junit.jupiter.api.Assertions.assertFalse(body.contains("sqlite"),
                            "problem body must not contain database exception text: " + body);
                    org.junit.jupiter.api.Assertions.assertFalse(body.contains("UNIQUE constraint"),
                            "problem body must not contain raw constraint text: " + body);
                });
    }

}
