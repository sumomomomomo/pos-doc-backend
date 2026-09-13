package horse.sumomo.pos_doc_backend.security;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Configuration-boundary tests for the validated {@code app.security} group
 * (Task 11 configuration tests 1, 4, 5, 6, 7, 8 and 9).
 */
class SecurityPropertiesTest {

	private static SecurityProperties.Google google(List<String> viewer, List<String> reviewer) {
		return new SecurityProperties.Google(viewer, reviewer);
	}

	private static SecurityProperties.Google valid() {
		return google(List.of("sub-viewer"), List.of("sub-reviewer"));
	}

	// 1. Google mode rejects empty allowlists.
	@Test
	void googleModeRejectsEmptyAllowlists() {
		assertThrows(IllegalArgumentException.class,
				() -> new SecurityProperties("google", google(List.of(), List.of()), List.of(), "/", ""));
	}

	@Test
	void googleModeRejectsBlankOnlyAllowlists() {
		assertThrows(IllegalArgumentException.class,
				() -> new SecurityProperties("google", google(List.of("  "), List.of()), List.of(), "/", ""));
	}

	// 4. Duplicate or malformed subject configuration is rejected deterministically.
	@Test
	void duplicateViewerSubjectRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new SecurityProperties("google", google(List.of("a", "a"), List.of()), List.of(), "/", ""));
	}

	@Test
	void subjectInBothViewerAndReviewerRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new SecurityProperties("google", google(List.of("a"), List.of("a")), List.of(), "/", ""));
	}

	// 5. Absolute/protocol-relative login redirects are rejected.
	@Test
	void absoluteRedirectRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new SecurityProperties("google", valid(), List.of(), "https://evil.com", ""));
	}

	@Test
	void protocolRelativeRedirectRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new SecurityProperties("google", valid(), List.of(), "//evil.com", ""));
	}

	@Test
	void redirectWithQueryRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new SecurityProperties("google", valid(), List.of(), "/?q=1", ""));
	}

	@Test
	void redirectWithFragmentRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new SecurityProperties("google", valid(), List.of(), "/#f", ""));
	}

	@Test
	void sameOriginRelativeRedirectAccepted() {
		SecurityProperties p = new SecurityProperties("google", valid(), List.of(), "/app/dashboard", "");
		assertEquals("/app/dashboard", p.postLoginRedirect());
	}

	@Test
	void blankRedirectDefaultsToRoot() {
		SecurityProperties p = new SecurityProperties("google", valid(), List.of(), "", "");
		assertEquals("/", p.postLoginRedirect());
	}

	// 6. Wildcard/path/query/fragment CORS origins are rejected.
	@Test
	void wildcardOriginRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new SecurityProperties("google", valid(), List.of("*"), "/", ""));
	}

	@Test
	void originWithPathRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new SecurityProperties("google", valid(), List.of("https://example.com/path"), "/", ""));
	}

	@Test
	void originWithQueryRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new SecurityProperties("google", valid(), List.of("https://example.com/?q=1"), "/", ""));
	}

	@Test
	void originWithFragmentRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new SecurityProperties("google", valid(), List.of("https://example.com/#f"), "/", ""));
	}

	@Test
	void nonHttpSchemeOriginRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new SecurityProperties("google", valid(), List.of("ftp://example.com"), "/", ""));
	}

	// 7. HTTPS origins and the explicit localhost HTTP exceptions are accepted.
	@Test
	void httpsOriginAccepted() {
		SecurityProperties p = new SecurityProperties("google", valid(), List.of("https://example.com"), "/", "");
		assertEquals(List.of("https://example.com"), p.allowedOrigins());
	}

	@Test
	void localhostHttpOriginAccepted() {
		SecurityProperties p = new SecurityProperties("google", valid(), List.of("http://localhost"), "/", "");
		assertEquals(List.of("http://localhost"), p.allowedOrigins());
	}

	@Test
	void loopbackHttpOriginAccepted() {
		SecurityProperties p = new SecurityProperties("google", valid(), List.of("http://127.0.0.1"), "/", "");
		assertEquals(List.of("http://127.0.0.1"), p.allowedOrigins());
	}

	@Test
	void nonLocalhostHttpOriginRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new SecurityProperties("google", valid(), List.of("http://example.com"), "/", ""));
	}

	// 8. Stack-test mode requires a 64-hex token.
	@Test
	void stackTestModeRequiresToken() {
		assertThrows(IllegalArgumentException.class,
				() -> new SecurityProperties("stack-test", valid(), List.of(), "/", ""));
	}

	@Test
	void stackTestModeRejectsShortToken() {
		assertThrows(IllegalArgumentException.class,
				() -> new SecurityProperties("stack-test", valid(), List.of(), "/", "abc123"));
	}

	@Test
	void stackTestModeRejectsNonHexToken() {
		assertThrows(IllegalArgumentException.class,
				() -> new SecurityProperties("stack-test", valid(), List.of(), "/", "z".repeat(64)));
	}

	@Test
	void stackTestModeAccepts64HexToken() {
		SecurityProperties p = new SecurityProperties("stack-test", valid(), List.of(), "/", "0".repeat(64));
		assertTrue(p.isStackTest());
	}

	// 9. Google mode rejects a configured stack-test token.
	@Test
	void googleModeRejectsStackTestToken() {
		assertThrows(IllegalArgumentException.class,
				() -> new SecurityProperties("google", valid(), List.of(), "/", "0".repeat(64)));
	}

	@Test
	void invalidModeRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new SecurityProperties("bogus", valid(), List.of(), "/", ""));
	}

}
