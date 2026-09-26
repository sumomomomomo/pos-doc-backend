package horse.sumomo.pos_doc_backend.security;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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
import horse.sumomo.pos_doc_backend.content.SearchPageArchiveService;
import horse.sumomo.pos_doc_backend.ingestion.application.PosArchiveIntakeService;
import horse.sumomo.pos_doc_backend.ingestion.application.PosDocumentListService;
import horse.sumomo.pos_doc_backend.review.IngestionJobReadService;
import horse.sumomo.pos_doc_backend.review.PosRecordCommandService;
import horse.sumomo.pos_doc_backend.review.PosRecordReadService;
import horse.sumomo.pos_doc_backend.review.PosRecordSearchService;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
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
	@MockitoBean
	private SearchPageArchiveService pageArchiveService;

	@Test
	void searchPageArchiveRequiresReviewerAndCsrf() throws Exception {
		String ids = "[\"" + POS_ID + "\"]";
		this.mockMvc.perform(post("/pos-records/search-page-archive")
				.contentType(MediaType.APPLICATION_JSON).content(ids).with(OidcTestAuth.oidc(VIEWER, false)).with(csrf()))
				.andExpect(status().isForbidden());
		this.mockMvc.perform(post("/pos-records/search-page-archive")
				.contentType(MediaType.APPLICATION_JSON).content(ids).with(OidcTestAuth.oidc(REVIEWER, true)))
				.andExpect(status().isForbidden());
		Path archive = Files.createTempFile("search-page-security-test", ".zip");
		byte[] zipBytes = {0x50, 0x4b, 0x05, 0x06};
		Files.write(archive, zipBytes);
		when(pageArchiveService.create(List.of(POS_ID))).thenReturn(archive);
		this.mockMvc.perform(post("/pos-records/search-page-archive")
				.contentType(MediaType.APPLICATION_JSON).content(ids).with(OidcTestAuth.oidc(REVIEWER, true)).with(csrf()))
				.andExpect(status().isOk())
				.andExpect(header().string("Content-Disposition", "attachment; filename=pos-search-page.zip"))
				.andExpect(content().bytes(zipBytes));
		assertTrue(Files.notExists(archive));
	}

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

	// 2b. With the real servlet context path (/api/v1) the generated Google
	//     redirect_uri must be the callback under that context path, because the
	//     OAuth2 callback endpoint is served at /api/v1/login/oauth2/code/google.
	@Test
	void googleCallbackUriIncludesTheConfiguredContextPath() throws Exception {
		this.mockMvc.perform(get("/api/v1/oauth2/authorization/google").contextPath("/api/v1"))
				.andExpect(status().is3xxRedirection())
				.andExpect(result -> {
					String redirect = result.getResponse().getRedirectedUrl();
					String decoded = URLDecoder.decode(redirect, StandardCharsets.UTF_8);
					assertTrue(decoded.contains("/api/v1/login/oauth2/code/google"),
							"redirect_uri must include the /api/v1 context path: " + decoded);
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

	// 9. /auth/me returns only email, display name, and roles and sets no-store.
	//    (The XSRF-TOKEN cookie emission and its exact production attributes are
	//    proven in SpaCsrfRoundTripTest, because .with(csrf()) in this shared
	//    context rewrites the CsrfFilter repository and would suppress the cookie.)
	@Test
	void currentUserExposesOnlyTheAgreedFieldsAndNoStoreAndCsrfCookie() throws Exception {
		this.mockMvc.perform(get("/auth/me").with(OidcTestAuth.oidc(VIEWER, false)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.email").isNotEmpty())
				.andExpect(jsonPath("$.displayName").isNotEmpty())
				.andExpect(jsonPath("$.roles").isArray())
				.andExpect(header().string("Cache-Control", "no-store"))
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

	// 13. With no allowed origins configured (the default), a preflight from any
	//     origin receives no CORS permission headers.
	@Test
	void emptyAllowedOriginsEmitsNoCorsPermissionHeaders() throws Exception {
		this.mockMvc.perform(options("/auth/me")
					.header("Origin", "https://spa.example.com")
					.header("Access-Control-Request-Method", "POST"))
				.andExpect(result -> {
					String acao = result.getResponse().getHeader("Access-Control-Allow-Origin");
					assertTrue(acao == null, "no Access-Control-Allow-Origin when allowed-origins is empty, got: " + acao);
					assertTrue(result.getResponse().getHeader("Access-Control-Allow-Credentials") == null,
							"no Access-Control-Allow-Credentials when allowed-origins is empty");
				});
	}

	// 14. HTTP boundary: the extracted PDF streams a non-empty body byte-for-byte
	//     with every required security/content header (inline disposition).
	@Test
	void pdfContentStreamsNonEmptyBodyWithRequiredHeaders() throws Exception {
		byte[] pdfBytes = ("%PDF-1.4\n% synthetic content\n%%EOF\n").getBytes(StandardCharsets.UTF_8);
		mockContentStreamingBytes(pdfBytes, new byte[] {0x50, 0x4b});

		this.mockMvc.perform(get("/pos-records/{posRecordId}/documents/{documentId}/content", POS_ID, DOC_ID)
					.with(OidcTestAuth.oidc(REVIEWER, true)))
				.andExpect(status().isOk())
				.andExpect(header().string("Content-Type", "application/pdf"))
				.andExpect(header().string("Content-Length", String.valueOf(pdfBytes.length)))
				.andExpect(header().string("Cache-Control", "no-store, no-cache, must-revalidate"))
				.andExpect(header().string("Pragma", "no-cache"))
				.andExpect(header().string("X-Content-Type-Options", "nosniff"))
				.andExpect(result -> {
					String disposition = result.getResponse().getHeader("Content-Disposition");
					assertTrue(disposition != null && disposition.startsWith("inline") && disposition.contains("first.pdf"),
							"PDF must be inline with the safe filename, got: " + disposition);
					assertArrayEquals(pdfBytes, result.getResponse().getContentAsByteArray(),
							"PDF body must stream byte-for-byte");
				});
	}

	// 15. HTTP boundary: the original ZIP streams a non-empty body byte-for-byte
	//     with every required header (attachment disposition).
	@Test
	void sourceArchiveContentStreamsNonEmptyBodyWithRequiredHeaders() throws Exception {
		byte[] zipBytes = new byte[] {0x50, 0x4b, 0x03, 0x04, 0x0a, 0x0b, 0x0c, 0x0d};
		mockContentStreamingBytes(new byte[] {0x25, 0x50}, zipBytes);

		this.mockMvc.perform(get("/pos-records/{posRecordId}/source-archive/content", POS_ID)
					.with(OidcTestAuth.oidc(REVIEWER, true)))
				.andExpect(status().isOk())
				.andExpect(header().string("Content-Type", "application/zip"))
				.andExpect(header().string("Content-Length", String.valueOf(zipBytes.length)))
				.andExpect(header().string("Cache-Control", "no-store, no-cache, must-revalidate"))
				.andExpect(header().string("Pragma", "no-cache"))
				.andExpect(header().string("X-Content-Type-Options", "nosniff"))
				.andExpect(result -> {
					String disposition = result.getResponse().getHeader("Content-Disposition");
					assertTrue(disposition != null && disposition.startsWith("attachment") && disposition.contains("archive.zip"),
							"ZIP must be an attachment with the safe filename, got: " + disposition);
					assertArrayEquals(zipBytes, result.getResponse().getContentAsByteArray(),
							"ZIP body must stream byte-for-byte");
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

	/**
	 * Stubs the content service so the PDF descriptor carries {@code pdfBytes}
	 * and the source-archive descriptor carries {@code zipBytes}, and
	 * {@code streamContent} writes the matching bytes to the target stream.
	 */
	private void mockContentStreamingBytes(byte[] pdfBytes, byte[] zipBytes) {
		ContentDescriptor pdf = new ContentDescriptor("so-pdf", "archives/1/2.pdf", "application/pdf",
				"first.pdf", (long) pdfBytes.length);
		ContentDescriptor zip = new ContentDescriptor("so-zip", "archives/1/3.zip", "application/zip",
				"archive.zip", (long) zipBytes.length);
		when(this.contentService.pdfDescriptor(POS_ID, DOC_ID)).thenReturn(pdf);
		when(this.contentService.sourceArchiveDescriptor(POS_ID)).thenReturn(zip);
		doAnswer(invocation -> {
			ContentDescriptor descriptor = invocation.getArgument(0);
			java.io.OutputStream out = invocation.getArgument(1);
			byte[] bytes = "application/pdf".equals(descriptor.contentType()) ? pdfBytes : zipBytes;
			out.write(bytes);
			out.flush();
			return null;
		}).when(this.contentService).streamContent(any(ContentDescriptor.class), any(java.io.OutputStream.class));
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
