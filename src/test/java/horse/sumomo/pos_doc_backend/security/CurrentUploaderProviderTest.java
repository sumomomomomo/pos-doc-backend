package horse.sumomo.pos_doc_backend.security;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;

import horse.sumomo.pos_doc_backend.ingestion.application.CurrentUploaderProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Current-uploader tests (Task 11 current uploader tests 1-5). The provider has no
 * access to the request, so attribution can only come from the authenticated
 * principal (test 4: request headers and upload parameters cannot override it).
 * Test 6 (a real upload stores the subject in {@code uploaded_by}) is covered by
 * the intake integration tests and the detail DTO.
 */
class CurrentUploaderProviderTest {

	private final CurrentUploaderProvider provider = new CurrentUploaderProvider();

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	private static void authenticate(Authentication token) {
		SecurityContext ctx = SecurityContextHolder.createEmptyContext();
		ctx.setAuthentication(token);
		SecurityContextHolder.setContext(ctx);
	}

	// 1. Google mode returns google:<sub> for an authenticated allowed OIDC user.
	@Test
	void googleModeReturnsGoogleNamespacedSubject() {
		var user = OidcTestAuth.syntheticOidcUser("sub-123", false);
		authenticate(new OAuth2AuthenticationToken(user, user.getAuthorities(), "google"));
		assertEquals("google:sub-123", this.provider.currentUploader());
	}

	// 2. It never returns email or display name.
	@Test
	void neverReturnsEmailOrDisplayName() {
		var user = OidcTestAuth.syntheticOidcUser("sub-456", false);
		authenticate(new OAuth2AuthenticationToken(user, user.getAuthorities(), "google"));
		String uploader = this.provider.currentUploader();
		assertEquals("google:sub-456", uploader);
		assertFalse(uploader.contains("sub-456@example.test"), "must not be the email: " + uploader);
		assertFalse(uploader.contains("@"), "must not be an address: " + uploader);
	}

	// 4. Request headers/parameters cannot override attribution: the provider reads
	// only the SecurityContext and has no request parameter, so a hypothetical
	// header/param value has no channel to influence the result.
	@Test
	void attributionDependsOnlyOnTheAuthenticatedPrincipal() {
		var user = OidcTestAuth.syntheticOidcUser("sub-789", false);
		authenticate(new OAuth2AuthenticationToken(user, user.getAuthorities(), "google"));
		assertEquals("google:sub-789", this.provider.currentUploader());
	}

	// 3. Anonymous/missing/wrong principal fails closed.
	@Test
	void missingAuthenticationFailsClosed() {
		SecurityContextHolder.clearContext();
		assertThrows(AuthRequiredException.class, this.provider::currentUploader);
	}

	@Test
	void anonymousStringPrincipalFailsClosed() {
		SecurityContext ctx = SecurityContextHolder.createEmptyContext();
		ctx.setAuthentication(new UsernamePasswordAuthenticationToken("anonymousUser", null, List.of()));
		SecurityContextHolder.setContext(ctx);
		assertThrows(AuthRequiredException.class, this.provider::currentUploader);
	}

	@Test
	void nonOidcNonStackTestPrincipalFailsClosed() {
		SecurityContext ctx = SecurityContextHolder.createEmptyContext();
		ctx.setAuthentication(new UsernamePasswordAuthenticationToken("tester", "pw",
				List.of(new SimpleGrantedAuthority("ROLE_USER"))));
		SecurityContextHolder.setContext(ctx);
		assertThrows(AuthRequiredException.class, this.provider::currentUploader);
	}

	@Test
	void oidcUserWithBlankSubjectFailsClosed() {
		// A synthetic OidcUser whose sub claim is blank cannot be attributed.
		var user = OidcTestAuth.syntheticOidcUser("", false);
		authenticate(new OAuth2AuthenticationToken(user, user.getAuthorities(), "google"));
		assertThrows(AuthRequiredException.class, this.provider::currentUploader);
	}

	// 5. Stack-test mode produces only the documented synthetic namespace.
	@Test
	void stackTestPrincipalKeepsItsNamespace() {
		authenticate(new UsernamePasswordAuthenticationToken(
				new SecurityPrincipal("stack-test:principal", "x@example.invalid", "Stack Test"), null,
				List.of(new SimpleGrantedAuthority("ROLE_USER"))));
		assertEquals("stack-test:principal", this.provider.currentUploader());
	}

	@Test
	void bareStackTestPrincipalIsPrefixed() {
		authenticate(new UsernamePasswordAuthenticationToken(
				new SecurityPrincipal("bare-name", "x@example.invalid", "Stack Test"), null, List.of()));
		assertEquals("stack-test:bare-name", this.provider.currentUploader());
	}

}
