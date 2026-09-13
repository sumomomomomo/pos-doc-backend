package horse.sumomo.pos_doc_backend.security;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.yourcompany.pos.api.model.IngestionJob;
import com.yourcompany.pos.api.model.JobStatus;
import com.yourcompany.pos.api.model.PosRecord;
import com.yourcompany.pos.api.model.PosRecordSearchPage;
import com.yourcompany.pos.api.model.PosRecordStatus;
import com.yourcompany.pos.api.model.StorageObjectSummary;

import horse.sumomo.pos_doc_backend.content.ContentDescriptor;
import horse.sumomo.pos_doc_backend.content.DocumentContentService;
import horse.sumomo.pos_doc_backend.ingestion.application.PosArchiveIntakeService;
import horse.sumomo.pos_doc_backend.ingestion.application.PosDocumentListService;
import horse.sumomo.pos_doc_backend.review.IngestionJobReadService;
import horse.sumomo.pos_doc_backend.review.PosRecordCommandService;
import horse.sumomo.pos_doc_backend.review.PosRecordReadService;
import horse.sumomo.pos_doc_backend.review.PosRecordSearchService;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security filter-chain MVC tests (Task 11 security filter-chain tests 1-12) using
 * the real filter chain and Spring Security test support. No
 * {@code @AutoConfigureMockMvc(addFilters = false)} is used. The persistence-backed
 * collaborators are mocked so the tests prove the authorization/CSRF boundary, not
 * the business logic (covered by the review/content tests).
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GoogleSecurityFilterChainTest {

	private static final UUID POS_ID = UUID.randomUUID();
	private static final UUID DOC_ID = UUID.randomUUID();
	private static final UUID JOB_ID = UUID.randomUUID();
	private static final String VIEWER = "pos-doc-test-subject-viewer";
	private static final String REVIEWER = "pos-doc-test-subject-reviewer";

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
	@MockitoBean
	private DocumentContentService contentService;

	@DynamicPropertySource
	static void sqliteUrl(DynamicPropertyRegistry registry) throws Exception {
		Path dbFile = Files.createTempFile("pos-doc-security-chain-test", ".db");
		dbFile.toFile().deleteOnExit();
		Path.of(dbFile.toString() + "-wal").toFile().deleteOnExit();
		Path.of(dbFile.toString() + "-shm").toFile().deleteOnExit();
		registry.add("SQLITE_URL", () -> "jdbc:sqlite:" + dbFile);
	}

	// 1. Unauthenticated API reads and mutations return JSON 401, not redirects.
	@Test
	void unauthenticatedReadReturnsJson401() throws Exception {
		this.mockMvc.perform(get("/pos-records/{id}", POS_ID))
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
	}

	@Test
	void unauthenticatedMutationReturnsJson401() throws Exception {
		this.mockMvc.perform(delete("/pos-records/{id}", POS_ID).with(csrf()))
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
	}

	// 2. The explicit Google authorization endpoint redirects normally.
	@Test
	void googleAuthorizationEndpointRedirects() throws Exception {
		this.mockMvc.perform(get("/oauth2/authorization/google"))
				.andExpect(status().is3xxRedirection())
				.andExpect(result -> {
					String redirect = result.getResponse().getRedirectedUrl();
					assertTrue(redirect != null
							&& (redirect.contains("accounts.google.com") || redirect.contains("oauth2/authorize")),
							"must redirect to Google's authorization endpoint: " + redirect);
				});
	}

	// 3. USER can access detail, search, document list, job status, current user, logout.
	@Test
	void userCanReadDetail() throws Exception {
		when(this.readService.getRecord(POS_ID)).thenReturn(record(POS_ID));
		this.mockMvc.perform(get("/pos-records/{id}", POS_ID).with(OidcTestAuth.oidc(VIEWER, false)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(POS_ID.toString()));
	}

	@Test
	void userCanSearch() throws Exception {
		when(this.searchService.search(any())).thenReturn(new PosRecordSearchPage(List.of(), 0, 20, 0L, 0));
		this.mockMvc.perform(post("/pos-records/search").contentType(MediaType.APPLICATION_JSON).content("{}")
				.with(OidcTestAuth.oidc(VIEWER, false)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(0));
	}

	@Test
	void userCanListDocuments() throws Exception {
		when(this.documentListService.listDocuments(POS_ID)).thenReturn(List.of());
		this.mockMvc.perform(get("/pos-records/{id}/documents", POS_ID).with(OidcTestAuth.oidc(VIEWER, false)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void userCanReadJobStatus() throws Exception {
		when(this.ingestionJobReadService.getJob(JOB_ID))
				.thenReturn(new IngestionJob(JOB_ID, POS_ID, JobStatus.QUEUED, 0,
						OffsetDateTime.parse("2026-01-02T03:04:05Z")));
		this.mockMvc.perform(get("/ingestion-jobs/{jobId}", JOB_ID).with(OidcTestAuth.oidc(VIEWER, false)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(JOB_ID.toString()));
	}

	@Test
	void userCanReadCurrentUser() throws Exception {
		this.mockMvc.perform(get("/auth/me").with(OidcTestAuth.oidc(VIEWER, false)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.email").isNotEmpty())
				.andExpect(jsonPath("$.roles").isArray());
	}

	@Test
	void userCanLogout() throws Exception {
		this.mockMvc.perform(post("/auth/logout").with(OidcTestAuth.oidc(VIEWER, false)).with(csrf()))
				.andExpect(status().isNoContent());
	}

	// 4. USER receives JSON 403 ACCESS_DENIED for upload, PATCH, verification, delete, PDF, ZIP.
	@Test
	void userCannotUpload() throws Exception {
		this.mockMvc.perform(multipart("/pos-records").file(zip()).with(OidcTestAuth.oidc(VIEWER, false)).with(csrf()))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
	}

	@Test
	void userCannotPatch() throws Exception {
		this.mockMvc.perform(patch("/pos-records/{id}", POS_ID)
				.contentType(MediaType.parseMediaType("application/merge-patch+json"))
				.content("{\"expectedVersion\":1,\"policyNumber\":\"P1\"}").with(OidcTestAuth.oidc(VIEWER, false)).with(csrf()))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
	}

	@Test
	void userCannotVerify() throws Exception {
		this.mockMvc.perform(post("/pos-records/{id}/verification", POS_ID)
				.contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":1}")
				.with(OidcTestAuth.oidc(VIEWER, false)).with(csrf()))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
	}

	@Test
	void userCannotDelete() throws Exception {
		this.mockMvc.perform(delete("/pos-records/{id}", POS_ID).with(OidcTestAuth.oidc(VIEWER, false)).with(csrf()))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
	}

	@Test
	void userCannotReadPdfContent() throws Exception {
		this.mockMvc.perform(
				get("/pos-records/{posRecordId}/documents/{documentId}/content", POS_ID, DOC_ID)
						.with(OidcTestAuth.oidc(VIEWER, false)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
	}

	@Test
	void userCannotReadSourceArchiveContent() throws Exception {
		this.mockMvc.perform(get("/pos-records/{posRecordId}/source-archive/content", POS_ID)
				.with(OidcTestAuth.oidc(VIEWER, false)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
	}

	// 5. REVIEWER can reach every route in the authorization matrix.
	@Test
	void reviewerCanReadDetail() throws Exception {
		when(this.readService.getRecord(POS_ID)).thenReturn(record(POS_ID));
		this.mockMvc.perform(get("/pos-records/{id}", POS_ID).with(OidcTestAuth.oidc(REVIEWER, true)))
				.andExpect(status().isOk());
	}

	@Test
	void reviewerCanReadPdfContent() throws Exception {
		mockContentStreaming();
		this.mockMvc.perform(get("/pos-records/{posRecordId}/documents/{documentId}/content", POS_ID, DOC_ID)
				.with(OidcTestAuth.oidc(REVIEWER, true)))
				.andExpect(status().isOk())
				.andExpect(header().string("Content-Type", "application/pdf"))
				.andExpect(header().string("X-Content-Type-Options", "nosniff"))
				.andExpect(header().string("Cache-Control", "no-store, no-cache, must-revalidate"));
	}

	@Test
	void reviewerCanReadSourceArchiveContent() throws Exception {
		mockContentStreaming();
		this.mockMvc.perform(get("/pos-records/{posRecordId}/source-archive/content", POS_ID)
				.with(OidcTestAuth.oidc(REVIEWER, true)))
				.andExpect(status().isOk())
				.andExpect(header().string("Content-Type", "application/zip"));
	}

	@Test
	void reviewerCanPatch() throws Exception {
		when(this.commandService.patch(any(UUID.class), any())).thenReturn(record(POS_ID));
		this.mockMvc.perform(patch("/pos-records/{id}", POS_ID)
				.contentType(MediaType.parseMediaType("application/merge-patch+json"))
				.content("{\"expectedVersion\":1,\"policyNumber\":\"P1\"}").with(OidcTestAuth.oidc(REVIEWER, true)).with(csrf()))
				.andExpect(status().isOk());
	}

	@Test
	void reviewerCanDelete() throws Exception {
		this.mockMvc.perform(delete("/pos-records/{id}", POS_ID).with(OidcTestAuth.oidc(REVIEWER, true)).with(csrf()))
				.andExpect(status().isNoContent());
	}

	// 6. Missing/invalid CSRF rejects every production mutation with CSRF_TOKEN_INVALID.
	@Test
	void mutationWithoutCsrfIsRejected() throws Exception {
		this.mockMvc.perform(delete("/pos-records/{id}", POS_ID).with(OidcTestAuth.oidc(REVIEWER, true)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
	}

	@Test
	void uploadWithoutCsrfIsRejected() throws Exception {
		this.mockMvc.perform(multipart("/pos-records").file(zip()).with(OidcTestAuth.oidc(REVIEWER, true)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
	}

	// 7. Valid CSRF permits those mutations to reach the mocked application service.
	@Test
	void validCsrfPermitsMutationToReachService() throws Exception {
		org.mockito.Mockito.doNothing().when(this.commandService).delete(POS_ID);
		this.mockMvc.perform(delete("/pos-records/{id}", POS_ID).with(OidcTestAuth.oidc(REVIEWER, true)).with(csrf()))
				.andExpect(status().isNoContent());
		org.mockito.Mockito.verify(this.commandService).delete(POS_ID);
	}

	// 8. Search remains authenticated but is the sole CSRF-exempt POST.
	@Test
	void searchIsAuthenticatedAndCsrfExempt() throws Exception {
		when(this.searchService.search(any())).thenReturn(new PosRecordSearchPage(List.of(), 0, 20, 0L, 0));
		this.mockMvc.perform(post("/pos-records/search").contentType(MediaType.APPLICATION_JSON).content("{}")
				.with(OidcTestAuth.oidc(VIEWER, false)))
				.andExpect(status().isOk());
	}

	@Test
	void searchIsStillAuthenticated() throws Exception {
		this.mockMvc.perform(post("/pos-records/search").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isUnauthorized());
	}

	// 9. /auth/me returns only email, display name, and roles; no-store; materializes XSRF-TOKEN.
	@Test
	void currentUserExposesOnlyTheAgreedFieldsAndNoStoreAndCsrfCookie() throws Exception {
		this.mockMvc.perform(get("/auth/me").with(OidcTestAuth.oidc(VIEWER, false)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.email").isNotEmpty())
				.andExpect(jsonPath("$.displayName").isNotEmpty())
				.andExpect(jsonPath("$.roles").isArray())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(cookie().exists("XSRF-TOKEN"))
				.andExpect(result -> {
					String body = result.getResponse().getContentAsString();
					assertTrue(!body.contains("sub-") && !body.contains("sub\""), "body must not leak the subject: " + body);
				});
	}

	// 10. Logout clears the CSRF cookie; a subsequent unauthenticated request is 401.
	@Test
	void logoutClearsCsrfCookieAndSubsequentRequestIsUnauthorized() throws Exception {
		this.mockMvc.perform(post("/auth/logout").with(OidcTestAuth.oidc(VIEWER, false)).with(csrf()))
				.andExpect(status().isNoContent())
				.andExpect(cookie().maxAge("XSRF-TOKEN", 0));
		this.mockMvc.perform(get("/pos-records/{id}", POS_ID))
				.andExpect(status().isUnauthorized());
	}

	// 11. Unmatched application routes are denied by default.
	@Test
	void unmatchedApplicationRouteIsDeniedByDefault() throws Exception {
		this.mockMvc.perform(get("/some/unmatched/app/route").with(OidcTestAuth.oidc(REVIEWER, true)))
				.andExpect(status().isForbidden());
	}

	// 12. Health remains public and exposes no sensitive details.
	@Test
	void healthIsPublic() throws Exception {
		this.mockMvc.perform(get("/actuator/health"))
				.andExpect(result -> {
					int status = result.getResponse().getStatus();
					assertTrue(status != 401 && status != 403,
							"health must be public (not auth-denied), got: " + status);
				})
				.andExpect(result -> {
					String body = result.getResponse().getContentAsString().toLowerCase();
					assertTrue(!body.contains("google_client_id") && !body.contains("client-secret"),
							"health must not expose credentials: " + body);
				});
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	private void mockContentStreaming() {
		ContentDescriptor pdf = new ContentDescriptor("so-pdf", "archives/1/2.pdf", "application/pdf", "first.pdf", 0L);
		ContentDescriptor zip = new ContentDescriptor("so-zip", "archives/1/3.zip", "application/zip", "archive.zip", 0L);
		when(this.contentService.pdfDescriptor(POS_ID, DOC_ID)).thenReturn(pdf);
		when(this.contentService.sourceArchiveDescriptor(POS_ID)).thenReturn(zip);
		doAnswer(invocation -> {
			java.io.OutputStream out = invocation.getArgument(1);
			out.flush();
			return null;
		}).when(this.contentService).streamContent(any(ContentDescriptor.class), any());
	}

	private static MockMultipartFile zip() {
		return new MockMultipartFile("file", "EREF-CHAIN.zip", "application/zip", new byte[] {1, 2, 3});
	}

	private static PosRecord record(UUID id) {
		return new PosRecord(id, PosRecordStatus.REVIEW_REQUIRED,
				new StorageObjectSummary(UUID.randomUUID(), "archive.zip", "application/zip", 128L,
						"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"),
				OffsetDateTime.parse("2026-01-02T03:04:05Z"), OffsetDateTime.parse("2026-01-03T04:05:06Z"),
				"google:" + REVIEWER, 1L)
				.erefNumber("EREF-2026-00123")
				.policyNumber("P12345678")
				.policyholderName("Jane Tan")
				.consultantName("Consultant One")
				.policyCreateDate(LocalDate.of(2026, 2, 1));
	}

}
