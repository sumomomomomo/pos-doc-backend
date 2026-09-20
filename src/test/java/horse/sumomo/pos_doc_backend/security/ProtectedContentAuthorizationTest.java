package horse.sumomo.pos_doc_backend.security;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the protected-content authorization decision (Task 11
 * corrective review).
 *
 * <p>The content matchers use Spring Security's {@code PathPatternRequestMatcher},
 * which — like Spring MVC's own {@code PathPattern} routing — ignores matrix
 * (semicolon) variables and matches relative to the servlet context path. This
 * proves a request whose path carries a {@code ;x=1} component is still treated as
 * protected content (requiring {@code ROLE_REVIEWER}) and is <em>not</em> downgraded
 * to a plain read (which would only require {@code ROLE_USER}).
 *
 * <p>The decision is exercised directly rather than through the servlet stack: the
 * container rejects a {@code ;x=1} request at path-matching (a 400, then a denied
 * {@code /error} dispatch) before it can reach the controller, so a unit test of the
 * decision is the precise way to prove the authorization is aligned with Spring's
 * {@code PathPattern} behavior.
 */
class ProtectedContentAuthorizationTest {

	private static final String CONTEXT = "/api/v1";
	private static final UUID POS_ID = UUID.randomUUID();
	private static final UUID DOC_ID = UUID.randomUUID();

	@Test
	void pdfContentWithMatrixVariableRequiresReviewer() {
		MockHttpServletRequest request = contentRequest(
				"/pos-records/" + POS_ID + "/documents/" + DOC_ID + "/content;x=1");
		assertFalse(GoogleSecurityConfiguration.decide(request, user()),
				"USER must be denied PDF content with a matrix variable");
		assertTrue(GoogleSecurityConfiguration.decide(request, reviewer()),
				"REVIEWER must be allowed PDF content with a matrix variable");
	}

	@Test
	void sourceArchiveContentWithMatrixVariableRequiresReviewer() {
		MockHttpServletRequest request = contentRequest(
				"/pos-records/" + POS_ID + "/source-archive/content;x=1");
		assertFalse(GoogleSecurityConfiguration.decide(request, user()),
				"USER must be denied source-archive content with a matrix variable");
		assertTrue(GoogleSecurityConfiguration.decide(request, reviewer()),
				"REVIEWER must be allowed source-archive content with a matrix variable");
	}

	// Control: the plain (no matrix variable) content paths are protected the same
	// way, confirming the matrix variable does not change the decision.
	@Test
	void pdfContentPlainRequiresReviewer() {
		MockHttpServletRequest request = contentRequest(
				"/pos-records/" + POS_ID + "/documents/" + DOC_ID + "/content");
		assertFalse(GoogleSecurityConfiguration.decide(request, user()));
		assertTrue(GoogleSecurityConfiguration.decide(request, reviewer()));
	}

	@Test
	void sourceArchiveContentPlainRequiresReviewer() {
		MockHttpServletRequest request = contentRequest(
				"/pos-records/" + POS_ID + "/source-archive/content");
		assertFalse(GoogleSecurityConfiguration.decide(request, user()));
		assertTrue(GoogleSecurityConfiguration.decide(request, reviewer()));
	}

	private static MockHttpServletRequest contentRequest(String appPath) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", CONTEXT + appPath);
		request.setContextPath(CONTEXT);
		return request;
	}

	private static Authentication user() {
		return new UsernamePasswordAuthenticationToken("viewer", "x",
				List.<GrantedAuthority>of(new SimpleGrantedAuthority("ROLE_USER")));
	}

	private static Authentication reviewer() {
		return new UsernamePasswordAuthenticationToken("reviewer", "x",
				List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_REVIEWER")));
	}

}
