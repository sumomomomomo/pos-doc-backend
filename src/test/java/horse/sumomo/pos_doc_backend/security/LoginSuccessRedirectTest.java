package horse.sumomo.pos_doc_backend.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Regression test for the post-Google-login redirect target.
 *
 * <p>The backend is served under the {@code /api/v1} servlet context path. A plain
 * {@code defaultSuccessUrl("/pos/", true)} resolves the target against that context
 * path and redirects to {@code /api/v1/pos/} — which the backend does not host and
 * rejects with {@code 403}. {@code GoogleSecurityConfiguration.frontendSuccessHandler}
 * must instead redirect to the frontend route {@code /pos/} at the site root (no
 * context-path prefix), even when the request carries the {@code /api/v1} context
 * path.
 *
 * <p>This covers a different boundary from {@link OauthForwardedHeaderTest}: that test
 * asserts the callback URL sent <em>to</em> Google; this test asserts the frontend
 * redirect that happens <em>after</em> Google returns.
 */
class LoginSuccessRedirectTest {

	@Test
	void loginSuccessRedirectIsOutsideBackendContextPath() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setContextPath("/api/v1");
		request.setRequestURI("/api/v1/login/oauth2/code/google");

		MockHttpServletResponse response = new MockHttpServletResponse();

		AuthenticationSuccessHandler handler =
				GoogleSecurityConfiguration.frontendSuccessHandler("/pos/");

		handler.onAuthenticationSuccess(
				request,
				response,
				new TestingAuthenticationToken("user", "unused", "ROLE_USER"));

		assertEquals(302, response.getStatus());
		assertEquals("/pos/", response.getRedirectedUrl());
		assertFalse(response.getRedirectedUrl().startsWith("/api/v1"));
	}
}
