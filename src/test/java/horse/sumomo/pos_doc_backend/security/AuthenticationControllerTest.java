package horse.sumomo.pos_doc_backend.security;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MVC tests for the authentication controller endpoints ({@code /auth/me} and
 * {@code /auth/logout}). These complement the broader
 * {@link GoogleSecurityFilterChainTest} by asserting the exact response contract
 * of the auth endpoints: the {@code CurrentUser} fields (email, displayName,
 * roles), the no-store cache directive, the materialized {@code XSRF-TOKEN}
 * cookie, and the 204 logout.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthenticationControllerTest {

	private static final String VIEWER = "pos-doc-test-subject-viewer";
	private static final String REVIEWER = "pos-doc-test-subject-reviewer";

	@Autowired
	private MockMvc mockMvc;

	@Test
	void getCurrentUserReturnsAgreedFieldsNoStoreAndCsrfCookie() throws Exception {
		this.mockMvc.perform(get("/auth/me").with(OidcTestAuth.oidc(VIEWER, false)).with(csrf()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.email").isNotEmpty())
				.andExpect(jsonPath("$.displayName").isNotEmpty())
				.andExpect(jsonPath("$.roles").isArray())
				.andExpect(jsonPath("$.roles").value(hasItem("USER")))
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(header().string("X-Content-Type-Options", "nosniff"))
				.andExpect(cookie().exists("XSRF-TOKEN"));
	}

	@Test
	void getCurrentUserForReviewerIncludesReviewerRole() throws Exception {
		this.mockMvc.perform(get("/auth/me").with(OidcTestAuth.oidc(REVIEWER, true)).with(csrf()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.roles").value(
						org.hamcrest.Matchers.containsInAnyOrder("USER", "REVIEWER")));
	}

	@Test
	void getCurrentUserForUnauthenticatedIsUnauthorized() throws Exception {
		this.mockMvc.perform(get("/auth/me").with(csrf()))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
	}

	@Test
	void logoutReturnsNoContentForAuthenticatedUser() throws Exception {
		this.mockMvc.perform(post("/auth/logout").with(OidcTestAuth.oidc(VIEWER, false)).with(csrf()))
				.andExpect(status().isNoContent());
	}

	@Test
	void logoutForUnauthenticatedIsANoOpNoContent() throws Exception {
		// The Spring Security logout filter handles POST /auth/logout before the
		// authorization check; for an unauthenticated request it is a safe no-op
		// (clearing an absent session) and still returns 204.
		this.mockMvc.perform(post("/auth/logout").with(csrf()))
				.andExpect(status().isNoContent());
	}

}
