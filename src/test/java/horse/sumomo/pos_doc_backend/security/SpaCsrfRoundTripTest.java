package horse.sumomo.pos_doc_backend.security;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.yourcompany.pos.api.model.PosRecord;
import com.yourcompany.pos.api.model.PosRecordPatch;
import com.yourcompany.pos.api.model.PosRecordSearchPage;
import com.yourcompany.pos.api.model.PosRecordStatus;
import com.yourcompany.pos.api.model.StorageObjectSummary;

import horse.sumomo.pos_doc_backend.content.DocumentContentService;
import horse.sumomo.pos_doc_backend.ingestion.application.PosArchiveIntakeService;
import horse.sumomo.pos_doc_backend.ingestion.application.PosDocumentListService;
import horse.sumomo.pos_doc_backend.review.IngestionJobReadService;
import horse.sumomo.pos_doc_backend.review.PosRecordCommandService;
import horse.sumomo.pos_doc_backend.review.PosRecordReadService;
import horse.sumomo.pos_doc_backend.review.PosRecordSearchService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Realistic browser-style CSRF round-trip and session/logout tests for the
 * production (Google) chain.
 *
 * <p>This class runs in a <em>fresh</em> application context (
 * {@link DirtiesContext.ClassMode#BEFORE_CLASS}) and never uses the
 * {@code .with(csrf())} post-processor, which rewrites the shared
 * {@code CsrfFilter}'s token repository and would suppress the real
 * {@code XSRF-TOKEN} cookie. Instead the tests drive the exact browser flow:
 * {@code GET /auth/me} materializes the {@code XSRF-TOKEN} cookie, and a
 * subsequent mutation submits that <em>exact</em> cookie value in the
 * {@code X-XSRF-TOKEN} header.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class SpaCsrfRoundTripTest {

	private static final UUID POS_ID = UUID.randomUUID();
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
		Path dbFile = Files.createTempFile("pos-doc-spa-csrf-test", ".db");
		dbFile.toFile().deleteOnExit();
		Path.of(dbFile.toString() + "-wal").toFile().deleteOnExit();
		Path.of(dbFile.toString() + "-shm").toFile().deleteOnExit();
		registry.add("SQLITE_URL", () -> "jdbc:sqlite:" + dbFile);
	}

	// 1. GET /auth/me emits the CSRF cookie with the exact production attributes.
	@Test
	void meEmitsCsrfCookieWithProductionAttributes() throws Exception {
		MvcResult result = this.mockMvc.perform(get("/auth/me").with(OidcTestAuth.oidc(VIEWER, false)))
				.andExpect(status().isOk())
				.andReturn();
		Cookie cookie = result.getResponse().getCookie("XSRF-TOKEN");
		assertNotNull(cookie, "GET /auth/me must emit the XSRF-TOKEN cookie");
		assertEquals("/", cookie.getPath(), "cookie path must be /");
		assertEquals(Boolean.TRUE, cookie.getSecure(), "cookie must be Secure");
		assertFalse(cookie.isHttpOnly(), "cookie must be readable by JavaScript (HttpOnly=false)");
		assertEquals("Lax", cookie.getAttribute("SameSite"), "cookie must be SameSite=Lax");
		assertTrue(cookie.getValue() != null && !cookie.getValue().isBlank(), "cookie must carry a token");
	}

	// 2. A mutation carrying the exact cookie value in X-XSRF-TOKEN succeeds
	//    (no .with(csrf()); the raw cookie value is the accepted token).
	@Test
	void mutationWithExactCookieValueInHeaderSucceeds() throws Exception {
		Cookie cookie = obtainCsrfCookie();
		when(this.commandService.patch(any(UUID.class), any(PosRecordPatch.class))).thenReturn(record(POS_ID));

		this.mockMvc.perform(patch("/pos-records/{id}", POS_ID)
				.contentType(MediaType.parseMediaType("application/merge-patch+json"))
				.content("{\"expectedVersion\":1,\"policyNumber\":\"P1\"}")
				.with(OidcTestAuth.oidc(REVIEWER, true))
				.cookie(cookie)
				.header("X-XSRF-TOKEN", cookie.getValue()))
				.andExpect(status().isOk());

		verify(this.commandService).patch(org.mockito.ArgumentMatchers.eq(POS_ID), any(PosRecordPatch.class));
	}

	// 3. A wrong raw value in X-XSRF-TOKEN is rejected with CSRF_TOKEN_INVALID.
	@Test
	void wrongRawHeaderValueReturnsCsrfInvalid() throws Exception {
		Cookie cookie = obtainCsrfCookie();

		this.mockMvc.perform(patch("/pos-records/{id}", POS_ID)
				.contentType(MediaType.parseMediaType("application/merge-patch+json"))
				.content("{\"expectedVersion\":1,\"policyNumber\":\"P1\"}")
				.with(OidcTestAuth.oidc(REVIEWER, true))
				.cookie(cookie)
				.header("X-XSRF-TOKEN", "definitely-not-the-cookie-value"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
	}

	// 4. Logout with the real CSRF token invalidates the actual session; the same
	//    session can no longer access a protected endpoint and the CSRF cookie is
	//    cleared. A fresh unauthenticated request is NOT used as the proof.
	@Test
	void logoutWithRealCsrfInvalidatesTheSession() throws Exception {
		MockHttpSession session = seededAuthenticatedSession(REVIEWER, true);

		// The seeded session authenticates this request and materializes the CSRF cookie.
		MvcResult me = this.mockMvc.perform(get("/auth/me").session(session))
				.andExpect(status().isOk())
				.andReturn();
		Cookie cookie = me.getResponse().getCookie("XSRF-TOKEN");
		assertNotNull(cookie, "authenticated GET /auth/me must emit the XSRF-TOKEN cookie");

		// Logout using that session and the real CSRF token.
		this.mockMvc.perform(post("/auth/logout").session(session)
				.cookie(cookie)
				.header("X-XSRF-TOKEN", cookie.getValue()))
				.andExpect(status().isNoContent())
				.andExpect(cookie().maxAge("XSRF-TOKEN", 0));

		// The very same session is now invalid (getAttribute throws on an
		// invalidated session), proving logout invalidated the original session.
		assertThrows(IllegalStateException.class, () -> session.getAttribute(
				HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY),
				"logout must invalidate the authenticated session");

		// ...and can no longer access a protected endpoint.
		this.mockMvc.perform(get("/pos-records/{id}", POS_ID).session(session))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	/**
	 * Performs an authenticated {@code GET /auth/me} and returns the emitted
	 * {@code XSRF-TOKEN} cookie (the raw token the browser must echo back).
	 */
	private Cookie obtainCsrfCookie() throws Exception {
		MvcResult result = this.mockMvc.perform(get("/auth/me").with(OidcTestAuth.oidc(VIEWER, false)))
				.andExpect(status().isOk())
				.andReturn();
		Cookie cookie = result.getResponse().getCookie("XSRF-TOKEN");
		assertNotNull(cookie, "GET /auth/me must emit the XSRF-TOKEN cookie");
		return cookie;
	}

	/**
	 * Creates a real {@link MockHttpSession} seeded with an authenticated security
	 * context under the standard session key, so the filter chain authenticates the
	 * request from the session (not from a per-request post-processor).
	 */
	private static MockHttpSession seededAuthenticatedSession(String subject, boolean reviewer) {
		MockHttpSession session = new MockHttpSession();
		var user = OidcTestAuth.syntheticOidcUser(subject, reviewer);
		var token = new OAuth2AuthenticationToken(user, user.getAuthorities(), "google");
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(token);
		session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
		return session;
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
