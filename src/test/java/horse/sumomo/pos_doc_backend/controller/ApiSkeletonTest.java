package horse.sumomo.pos_doc_backend.controller;

import horse.sumomo.pos_doc_backend.service.DummyIngestionJobService;
import horse.sumomo.pos_doc_backend.service.DummyPosRecordService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

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
 * HTTP-layer tests for the API skeleton. Loads only the two handwritten
 * controllers together with the real dummy services (no mocks) so that DTO
 * construction and JSON serialization are exercised end to end.
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

    @Test
    void uploadPosRecordReturns202WithFixedIdsAndLocation() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "dummy.zip", "application/zip",
                new byte[] {1, 2, 3, 4});

        // The generated @Pattern(".*\\\\S.*") on policyNumber (from the OpenAPI
        // pattern '.*\\S.*') requires a literal backslash+S sequence, so the
        // dummy value contains one.
        mockMvc.perform(multipart("/pos-records")
                        .file(file)
                        .param("policyNumber", "POLICY-UPLOAD-\\S001"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", FIXED_LOCATION))
                .andExpect(jsonPath("$.posRecordId").value(FIXED_POS_RECORD_ID))
                .andExpect(jsonPath("$.jobId").value(FIXED_JOB_ID))
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
                        .content("{\"expectedVersion\":1,\"policyCreateDate\":\"2026-02-01\"}"))
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
    void listPosDocumentsReturnsSingleDummyDocument() throws Exception {
        String pathId = "11111111-1111-1111-1111-111111111111";

        mockMvc.perform(get("/pos-records/{id}/documents", pathId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].posRecordId").value(pathId))
                .andExpect(jsonPath("$[0].storageObject.originalFilename").value("dummy-document.pdf"))
                .andExpect(jsonPath("$[0].processingStatus").value("COMPLETED"));
    }

    @Test
    void getIngestionJobEchoesPathJobIdWithDummyValues() throws Exception {
        String pathJobId = "22222222-2222-2222-2222-222222222222";

        mockMvc.perform(get("/ingestion-jobs/{jobId}", pathJobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(pathJobId))
                .andExpect(jsonPath("$.posRecordId").value(FIXED_POS_RECORD_ID))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.attemptCount").value(1));
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
}
