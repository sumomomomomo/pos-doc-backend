package horse.sumomo.pos_doc_backend.security;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Allowlist/authority mapper tests (Task 11 OIDC authorization tests 1-8), tested
 * independently of Google's network: every ID token is synthetic and validation of
 * the token itself (signature, audience) is Spring Security's responsibility, not
 * this mapper's.
 */
class OidcSubjectAuthorizerTest {

	private static final String ISSUER = OidcSubjectAuthorizer.GOOGLE_ISSUER;
	private static final Set<String> VIEWERS = Set.of("sub-viewer");
	private static final Set<String> REVIEWERS = Set.of("sub-reviewer");

	private final OidcSubjectAuthorizer authorizer = new OidcSubjectAuthorizer(VIEWERS, REVIEWERS, ISSUER);

	private static OidcIdToken idToken(String issuer, String sub, String email, Boolean emailVerified) {
		Map<String, Object> claims = new LinkedHashMap<>();
		claims.put("iss", issuer);
		if (sub != null) {
			claims.put("sub", sub);
		}
		if (email != null) {
			claims.put("email", email);
		}
		if (emailVerified != null) {
			claims.put("email_verified", emailVerified);
		}
		return new OidcIdToken("synthetic-token-value", Instant.now(), Instant.now().plusSeconds(300), claims);
	}

	private static List<String> authorityNames(List<GrantedAuthority> authorities) {
		return authorities.stream().map(GrantedAuthority::getAuthority).sorted().toList();
	}

	// 1. Allowed viewer succeeds with only ROLE_USER.
	@Test
	void allowedViewerGetsOnlyRoleUser() {
		List<GrantedAuthority> authorities =
				this.authorizer.authoritiesFor(idToken(ISSUER, "sub-viewer", "viewer@example.test", true));
		assertEquals(List.of("ROLE_USER"), authorityNames(authorities));
	}

	// 2. Allowed reviewer succeeds with ROLE_USER and ROLE_REVIEWER.
	@Test
	void allowedReviewerGetsBothRoles() {
		List<GrantedAuthority> authorities =
				this.authorizer.authoritiesFor(idToken(ISSUER, "sub-reviewer", "reviewer@example.test", true));
		assertEquals(List.of("ROLE_REVIEWER", "ROLE_USER"), authorityNames(authorities));
	}

	// 3. Unknown subject is rejected.
	@Test
	void unknownSubjectRejected() {
		OAuth2AuthenticationException e = assertThrows(OAuth2AuthenticationException.class,
				() -> this.authorizer.authoritiesFor(idToken(ISSUER, "sub-unknown", "u@example.test", true)));
		assertEquals(OidcSubjectAuthorizer.DENIAL_CATEGORY, e.getError().getErrorCode());
	}

	// 4. Missing/blank sub is rejected.
	@Test
	void missingSubRejected() {
		assertThrows(OAuth2AuthenticationException.class,
				() -> this.authorizer.authoritiesFor(idToken(ISSUER, null, "v@example.test", true)));
	}

	@Test
	void blankSubRejected() {
		assertThrows(OAuth2AuthenticationException.class,
				() -> this.authorizer.authoritiesFor(idToken(ISSUER, "   ", "v@example.test", true)));
	}

	// 5. Missing/false email_verified is rejected.
	@Test
	void unverifiedEmailRejected() {
		assertThrows(OAuth2AuthenticationException.class,
				() -> this.authorizer.authoritiesFor(idToken(ISSUER, "sub-viewer", "v@example.test", false)));
	}

	@Test
	void missingEmailVerifiedRejected() {
		assertThrows(OAuth2AuthenticationException.class,
				() -> this.authorizer.authoritiesFor(idToken(ISSUER, "sub-viewer", "v@example.test", null)));
	}

	// 6. Missing email is rejected.
	@Test
	void missingEmailRejected() {
		assertThrows(OAuth2AuthenticationException.class,
				() -> this.authorizer.authoritiesFor(idToken(ISSUER, "sub-viewer", null, true)));
	}

	// 7. Wrong issuer is rejected.
	@Test
	void wrongIssuerRejected() {
		assertThrows(OAuth2AuthenticationException.class,
				() -> this.authorizer.authoritiesFor(idToken("https://evil.example", "sub-viewer", "v@example.test",
						true)));
	}

	// 8. No failure/exception contains claims, email, subject, or token.
	@Test
	void denialExposesOnlyTheStableCategory() {
		String subject = "sub-secret-abcdef";
		String email = "secret@example.test";
		OAuth2AuthenticationException e = assertThrows(OAuth2AuthenticationException.class,
				() -> this.authorizer.authoritiesFor(idToken(ISSUER, subject, email, true)));
		String rendered = e.getMessage() + " | " + e.getError().getDescription() + " | " + e.getError().getErrorCode();
		assertFalse(rendered.contains(subject), "failure must not leak the subject: " + rendered);
		assertFalse(rendered.contains(email), "failure must not leak the email: " + rendered);
		assertFalse(rendered.contains("synthetic-token-value"), "failure must not leak the token: " + rendered);
		assertTrue(rendered.contains(OidcSubjectAuthorizer.DENIAL_CATEGORY),
				"failure must use the stable category: " + rendered);
	}

}
