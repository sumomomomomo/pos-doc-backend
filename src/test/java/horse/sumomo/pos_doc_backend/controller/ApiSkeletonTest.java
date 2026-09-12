package horse.sumomo.pos_doc_backend.controller;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.yourcompany.pos.api.model.IngestionJob;
import com.yourcompany.pos.api.model.JobStatus;
import com.yourcompany.pos.api.model.PosRecord;
import com.yourcompany.pos.api.model.PosRecordSearchPage;
import com.yourcompany.pos.api.model.PosRecordStatus;
import com.yourcompany.pos.api.model.PosRecordSummary;
import com.yourcompany.pos.api.model.StorageObjectSummary;
import com.yourcompany.pos.api.model.UploadAccepted;

import horse.sumomo.pos_doc_backend.ingestion.application.IntakeException;
import horse.sumomo.pos_doc_backend.ingestion.application.PosArchiveIntakeService;
import horse.sumomo.pos_doc_backend.ingestion.application.PosDocumentListService;
import horse.sumomo.pos_doc_backend.ingestion.application.UploadResult;
import horse.sumomo.pos_doc_backend.ingestion.archive.ArchiveValidationException;
import horse.sumomo.pos_doc_backend.review.IngestionJobReadService;
import horse.sumomo.pos_doc_backend.review.PosRecordApiException;
import horse.sumomo.pos_doc_backend.review.PosRecordApiException.Code;
import horse.sumomo.pos_doc_backend.review.PosRecordCommandService;
import horse.sumomo.pos_doc_backend.review.PosRecordReadService;
import horse.sumomo.pos_doc_backend.review.PosRecordSearchService;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
 * HTTP-layer tests for the POS-record and ingestion-job endpoints. Loads only
 * the two handwritten controllers; the persistence-backed collaborators are
 * mocked, so this proves the generated mappings (method, path, content type,
 * status), the DTO serialization, the sanitized error mapping, and request-body
 * validation. The real business logic is covered by the {@code review}
 * integration tests.
 *
 * <p>The generated mappings are relative to
 * {@code ${openapi.pOSDocumentIngestion.base-path}} (overridden to empty in
 * {@code application.yaml}); MockMvc does not apply the servlet context path,
 * so requests use the bare mapped paths.
 */
@WebMvcTest(controllers = {PosRecordsController.class, IngestionJobsController.class})
class ApiSkeletonTest {

	private static final MediaType MERGE_PATCH = MediaType.parseMediaType("application/merge-patch+json");

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private PosArchiveIntakeService intakeService;

	@MockitoBean
	private PosDocumentListService documentListService;

	@MockitoBean
	private PosRecordReadService readService;

	@MockitoBean
	private PosRecordSearchService searchService;

	@MockitoBean
	private PosRecordCommandService commandService;

	@MockitoBean
	private IngestionJobReadService ingestionJobReadService;

	// ------------------------------------------------------------------
	// upload (real service, mocked)
	// ------------------------------------------------------------------

	@Test
	void uploadPosRecordReturns202WithPersistedIdsAndLocation() throws Exception {
		UUID posRecordId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		when(this.intakeService.intake(any(), anyString()))
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
	void uploadWithoutFilePartReturns400() throws Exception {
		mockMvc.perform(multipart("/pos-records")
						.param("policyNumber", "POLICY-UPLOAD-001"))
				.andExpect(status().isBadRequest());
	}

	// ------------------------------------------------------------------
	// search
	// ------------------------------------------------------------------

	@Test
	void searchPosRecordsReturnsSummariesAndPaginationMetadata() throws Exception {
		UUID id = UUID.randomUUID();
		PosRecordSearchPage page = new PosRecordSearchPage(
				List.of(summary(id, PosRecordStatus.REVIEW_REQUIRED)), 0, 20, 1L, 1);
		when(this.searchService.search(any())).thenReturn(page);

		mockMvc.perform(post("/pos-records/search")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].id").value(id.toString()))
				.andExpect(jsonPath("$.items[0].erefNumber").value("EREF-2026-00123"))
				.andExpect(jsonPath("$.items[0].policyNumber").value("P12345678"))
				.andExpect(jsonPath("$.items[0].policyholderName").value("Jane Tan"))
				.andExpect(jsonPath("$.items[0].status").value("REVIEW_REQUIRED"))
				.andExpect(jsonPath("$.page").value(0))
				.andExpect(jsonPath("$.size").value(20))
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.totalPages").value(1));
	}

	@Test
	void searchInvalidSizeReturns400() throws Exception {
		mockMvc.perform(post("/pos-records/search")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"size\":0}"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	void searchInvalidThresholdReturns400() throws Exception {
		mockMvc.perform(post("/pos-records/search")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"minimumNameSimilarity\":1.5}"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	void searchBlankQueryReturns400() throws Exception {
		mockMvc.perform(post("/pos-records/search")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"erefNumber\":\"   \"}"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	// ------------------------------------------------------------------
	// detail read
	// ------------------------------------------------------------------

	@Test
	void getPosRecordReturnsPersistedRecord() throws Exception {
		UUID id = UUID.randomUUID();
		when(this.readService.getRecord(id)).thenReturn(record(id, PosRecordStatus.REVIEW_REQUIRED, 3L));

		mockMvc.perform(get("/pos-records/{id}", id))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id.toString()))
				.andExpect(jsonPath("$.erefNumber").value("EREF-2026-00123"))
				.andExpect(jsonPath("$.policyNumber").value("P12345678"))
				.andExpect(jsonPath("$.policyholderName").value("Jane Tan"))
				.andExpect(jsonPath("$.consultantName").value("Consultant One"))
				.andExpect(jsonPath("$.status").value("REVIEW_REQUIRED"))
				.andExpect(jsonPath("$.sourceArchive.originalFilename").value("archive.zip"))
				.andExpect(jsonPath("$.uploadedBy").value("uploader-subject"))
				.andExpect(jsonPath("$.version").value(3));
	}

	@Test
	void getPosRecordUnknownOrDeletedReturns404WithCode() throws Exception {
		UUID id = UUID.randomUUID();
		when(this.readService.getRecord(id)).thenThrow(new PosRecordApiException(Code.POS_RECORD_NOT_FOUND));

		mockMvc.perform(get("/pos-records/{id}", id))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.code").value("POS_RECORD_NOT_FOUND"));
	}

	@Test
	void getPosRecordNonUuidPathReturns400Problem() throws Exception {
		mockMvc.perform(get("/pos-records/{id}", "not-a-uuid"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	// ------------------------------------------------------------------
	// PATCH
	// ------------------------------------------------------------------

	@Test
	void updatePosRecordReturnsUpdatedRecord() throws Exception {
		UUID id = UUID.randomUUID();
		when(this.commandService.patch(any(UUID.class), any())).thenReturn(record(id, PosRecordStatus.REVIEW_REQUIRED, 4L));

		mockMvc.perform(patch("/pos-records/{id}", id)
						.contentType(MERGE_PATCH)
						.content("{\"expectedVersion\":3,\"policyNumber\":\"P12345678\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id.toString()))
				.andExpect(jsonPath("$.version").value(4));
	}

	@Test
	void updatePosRecordVersionMismatchReturns412WithCode() throws Exception {
		UUID id = UUID.randomUUID();
		when(this.commandService.patch(any(UUID.class), any()))
				.thenThrow(new PosRecordApiException(Code.POS_RECORD_VERSION_MISMATCH));

		mockMvc.perform(patch("/pos-records/{id}", id)
						.contentType(MERGE_PATCH)
						.content("{\"expectedVersion\":9,\"policyNumber\":\"P12345678\"}"))
				.andExpect(status().isPreconditionFailed())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(412))
				.andExpect(jsonPath("$.code").value("POS_RECORD_VERSION_MISMATCH"));
	}

	@Test
	void updatePosRecordNotReviewableReturns409WithCode() throws Exception {
		UUID id = UUID.randomUUID();
		when(this.commandService.patch(any(UUID.class), any()))
				.thenThrow(new PosRecordApiException(Code.POS_RECORD_NOT_REVIEWABLE));

		mockMvc.perform(patch("/pos-records/{id}", id)
						.contentType(MERGE_PATCH)
						.content("{\"expectedVersion\":0,\"policyNumber\":\"P12345678\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("POS_RECORD_NOT_REVIEWABLE"));
	}

	@Test
	void updatePosRecordDuplicatePolicyReturns409WithCode() throws Exception {
		UUID id = UUID.randomUUID();
		when(this.commandService.patch(any(UUID.class), any()))
				.thenThrow(new PosRecordApiException(Code.DUPLICATE_POLICY_NUMBER));

		mockMvc.perform(patch("/pos-records/{id}", id)
						.contentType(MERGE_PATCH)
						.content("{\"expectedVersion\":0,\"policyNumber\":\"P12345678\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("DUPLICATE_POLICY_NUMBER"));
	}

	@Test
	void updatePosRecordMissingExpectedVersionReturns400() throws Exception {
		UUID id = UUID.randomUUID();

		mockMvc.perform(patch("/pos-records/{id}", id)
						.contentType(MERGE_PATCH)
						.content("{\"policyNumber\":\"P12345678\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	void updatePosRecordNegativeVersionReturns400() throws Exception {
		UUID id = UUID.randomUUID();

		mockMvc.perform(patch("/pos-records/{id}", id)
						.contentType(MERGE_PATCH)
						.content("{\"expectedVersion\":-1,\"policyNumber\":\"P12345678\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	// ------------------------------------------------------------------
	// verification
	// ------------------------------------------------------------------

	@Test
	void verifyPosRecordReturnsCompletedRecord() throws Exception {
		UUID id = UUID.randomUUID();
		when(this.commandService.verify(any(UUID.class), any()))
				.thenReturn(record(id, PosRecordStatus.COMPLETED, 5L));

		mockMvc.perform(post("/pos-records/{id}/verification", id)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"expectedVersion\":4}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id.toString()))
				.andExpect(jsonPath("$.status").value("COMPLETED"))
				.andExpect(jsonPath("$.version").value(5));
	}

	@Test
	void verifyPosRecordWrongStatusReturns409WithCode() throws Exception {
		UUID id = UUID.randomUUID();
		when(this.commandService.verify(any(UUID.class), any()))
				.thenThrow(new PosRecordApiException(Code.POS_RECORD_NOT_REVIEWABLE));

		mockMvc.perform(post("/pos-records/{id}/verification", id)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"expectedVersion\":4}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("POS_RECORD_NOT_REVIEWABLE"));
	}

	@Test
	void verifyPosRecordStaleVersionReturns412WithCode() throws Exception {
		UUID id = UUID.randomUUID();
		when(this.commandService.verify(any(UUID.class), any()))
				.thenThrow(new PosRecordApiException(Code.POS_RECORD_VERSION_MISMATCH));

		mockMvc.perform(post("/pos-records/{id}/verification", id)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"expectedVersion\":0}"))
				.andExpect(status().isPreconditionFailed())
				.andExpect(jsonPath("$.code").value("POS_RECORD_VERSION_MISMATCH"));
	}

	@Test
	void verifyPosRecordIncompleteReturns422WithCode() throws Exception {
		UUID id = UUID.randomUUID();
		when(this.commandService.verify(any(UUID.class), any()))
				.thenThrow(new PosRecordApiException(Code.POS_RECORD_INCOMPLETE));

		mockMvc.perform(post("/pos-records/{id}/verification", id)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"expectedVersion\":0}"))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("POS_RECORD_INCOMPLETE"));
	}

	@Test
	void verifyPosRecordMissingExpectedVersionReturns400() throws Exception {
		UUID id = UUID.randomUUID();

		mockMvc.perform(post("/pos-records/{id}/verification", id)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	// ------------------------------------------------------------------
	// delete
	// ------------------------------------------------------------------

	@Test
	void deletePosRecordReturns204WithEmptyBody() throws Exception {
		UUID id = UUID.randomUUID();

		mockMvc.perform(delete("/pos-records/{id}", id))
				.andExpect(status().isNoContent())
				.andExpect(content().string(""));
	}

	@Test
	void deletePosRecordUnknownReturns404WithCode() throws Exception {
		UUID id = UUID.randomUUID();
		org.mockito.Mockito.doThrow(new PosRecordApiException(Code.POS_RECORD_NOT_FOUND))
				.when(this.commandService).delete(id);

		mockMvc.perform(delete("/pos-records/{id}", id))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.code").value("POS_RECORD_NOT_FOUND"));
	}

	// ------------------------------------------------------------------
	// documents
	// ------------------------------------------------------------------

	@Test
	void listPosDocumentsReturnsEmptyArrayWhenNoDocumentsExist() throws Exception {
		UUID id = UUID.randomUUID();
		when(this.documentListService.listDocuments(any(UUID.class))).thenReturn(List.of());

		mockMvc.perform(get("/pos-records/{id}/documents", id))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
	}

	// ------------------------------------------------------------------
	// ingestion job
	// ------------------------------------------------------------------

	@Test
	void getIngestionJobReturnsPersistedJob() throws Exception {
		UUID jobId = UUID.randomUUID();
		UUID posRecordId = UUID.randomUUID();
		IngestionJob job = new IngestionJob(jobId, posRecordId, JobStatus.QUEUED, 0,
				OffsetDateTime.parse("2026-01-02T03:04:05Z"));
		when(this.ingestionJobReadService.getJob(jobId)).thenReturn(job);

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
		when(this.ingestionJobReadService.getJob(jobId))
				.thenThrow(new IntakeException(IntakeException.Code.INGESTION_JOB_NOT_FOUND));

		mockMvc.perform(get("/ingestion-jobs/{jobId}", jobId))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.code").value("INGESTION_JOB_NOT_FOUND"))
				.andExpect(jsonPath("$.detail").value("The requested resource does not exist."));
	}

	// ------------------------------------------------------------------
	// upload failure contract
	// ------------------------------------------------------------------

	private MockMultipartFile zipFile(String filename, byte[] bytes) {
		return new MockMultipartFile("file", filename, "application/zip", bytes);
	}

	@Test
	void oversizeUploadReturns413ArchiveTooLarge() throws Exception {
		when(this.intakeService.intake(any(), any()))
				.thenThrow(new IntakeException(IntakeException.Code.ARCHIVE_TOO_LARGE));

		mockMvc.perform(multipart("/pos-records").file(zipFile("EREF-OVER.zip", new byte[] {1})))
				.andExpect(status().isPayloadTooLarge())
				.andExpect(jsonPath("$.code").value("ARCHIVE_TOO_LARGE"));
	}

	@Test
	void unsupportedArchiveTypeReturns415() throws Exception {
		when(this.intakeService.intake(any(), any()))
				.thenThrow(new ArchiveValidationException(
						ArchiveValidationException.Category.UNSUPPORTED_ARCHIVE_TYPE, "not a zip"));

		mockMvc.perform(multipart("/pos-records").file(zipFile("EREF-UNSUP.zip", new byte[] {1})))
				.andExpect(status().isUnsupportedMediaType())
				.andExpect(jsonPath("$.code").value("UNSUPPORTED_ARCHIVE_TYPE"));
	}

	@Test
	void invalidArchiveReturns422() throws Exception {
		when(this.intakeService.intake(any(), any()))
				.thenThrow(new ArchiveValidationException(
						ArchiveValidationException.Category.INVALID_ARCHIVE, "bad archive"));

		mockMvc.perform(multipart("/pos-records").file(zipFile("EREF-BAD.zip", new byte[] {1})))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("INVALID_ARCHIVE"));
	}

	@Test
	void duplicateErefReturns409WithDistinctCode() throws Exception {
		when(this.intakeService.intake(any(), any()))
				.thenThrow(new IntakeException(IntakeException.Code.DUPLICATE_EREF_NUMBER));

		mockMvc.perform(multipart("/pos-records")
						.file(zipFile("EREF-DUP.zip", new byte[] {1}))
						.param("policyNumber", "POLICY-DUP-001"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("DUPLICATE_EREF_NUMBER"));
	}

	@Test
	void internalErrorIsSanitized500AndLeaksNoSensitiveValues() throws Exception {
		when(this.intakeService.intake(any(), any()))
				.thenThrow(new IntakeException(IntakeException.Code.INGESTION_INTAKE_FAILED,
						new RuntimeException("sqlite: UNIQUE constraint failed: eref=EREF-SECRET-001, key=archives/x/y.zip")));

		mockMvc.perform(multipart("/pos-records")
						.file(zipFile("EREF-SECRET-001.zip", new byte[] {1}))
						.param("policyNumber", "POLICY-SECRET-001"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("INGESTION_INTAKE_FAILED"))
				.andExpect(result -> {
					String body = result.getResponse().getContentAsString();
					assertFalse(body.contains("EREF-SECRET-001"), "problem body must not contain the eRef: " + body);
					assertFalse(body.contains("POLICY-SECRET-001"),
							"problem body must not contain the policy number: " + body);
					assertFalse(body.contains("sqlite"), "problem body must not contain database text: " + body);
					assertFalse(body.contains("UNIQUE constraint"),
							"problem body must not contain raw constraint text: " + body);
				});
	}

	// ------------------------------------------------------------------
	// DTO builders (synthetic values only; no dummy constants)
	// ------------------------------------------------------------------

	private static PosRecord record(UUID id, PosRecordStatus status, long version) {
		return new PosRecord(id, status, storage(),
				OffsetDateTime.parse("2026-01-02T03:04:05Z"), OffsetDateTime.parse("2026-01-03T04:05:06Z"),
				"uploader-subject", version)
				.erefNumber("EREF-2026-00123")
				.policyNumber("P12345678")
				.policyholderName("Jane Tan")
				.consultantName("Consultant One")
				.policyCreateDate(LocalDate.of(2026, 2, 1));
	}

	private static PosRecordSummary summary(UUID id, PosRecordStatus status) {
		return new PosRecordSummary(id, status,
				OffsetDateTime.parse("2026-01-02T03:04:05Z"), OffsetDateTime.parse("2026-01-03T04:05:06Z"))
				.erefNumber("EREF-2026-00123")
				.policyNumber("P12345678")
				.policyholderName("Jane Tan")
				.consultantName("Consultant One")
				.policyCreateDate(LocalDate.of(2026, 2, 1));
	}

	private static StorageObjectSummary storage() {
		return new StorageObjectSummary(UUID.randomUUID(), "archive.zip", "application/zip", 128L,
				"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
	}

}
