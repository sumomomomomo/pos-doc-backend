package horse.sumomo.pos_doc_backend.security;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.web.client.RestTemplate;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves, against a <em>real embedded servlet container</em> (not MockMvc), that
 * {@code POST /auth/logout} expires the application's renamed session cookie
 * {@code POSDOCSESSION}.
 *
 * <p>MockMvc cannot demonstrate this: it clears the framework-managed
 * {@code XSRF-TOKEN} cookie on logout but does not replicate the container's
 * expiration of the (renamed) {@code JSESSIONID}-style session cookie, so
 * {@code POSDOCSESSION} never appears in a MockMvc logout response. The session
 * cookie is a container concern, so it must be verified on a real container.
 *
 * <p>A real Google login is not possible in a test, so a test-only filter seeds an
 * authenticated {@link HttpSession} (storing the security context under the standard
 * key) when the request carries the {@code X-Test-Seed-Auth} header. The filter runs
 * before the security chain, so the seeded request is already authenticated and the
 * container sets the {@code POSDOCSESSION} cookie.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(LogoutSessionCookieEmbeddedServerTest.SeedSessionConfiguration.class)
class LogoutSessionCookieEmbeddedServerTest {

	private static final String VIEWER = "pos-doc-test-subject-viewer";

	@LocalServerPort
	private int port;

	private RestTemplate rest;

	@BeforeEach
	void createRestTemplate() {
		this.rest = new RestTemplate();
	}

	@DynamicPropertySource
	static void sqliteUrl(DynamicPropertyRegistry registry) throws Exception {
		Path dbFile = Files.createTempFile("pos-doc-logout-cookie-test", ".db");
		dbFile.toFile().deleteOnExit();
		Path.of(dbFile.toString() + "-wal").toFile().deleteOnExit();
		Path.of(dbFile.toString() + "-shm").toFile().deleteOnExit();
		registry.add("SQLITE_URL", () -> "jdbc:sqlite:" + dbFile);
	}

	@Test
	void logoutExpiresTheRenamedSessionCookieOnTheRealContainer() {
		// 1. Seed an authenticated session. The container sets POSDOCSESSION and
		//    /auth/me materializes the XSRF-TOKEN cookie in the same response.
		HttpHeaders seedHeaders = new HttpHeaders();
		seedHeaders.set("X-Test-Seed-Auth", VIEWER);
		ResponseEntity<String> seed = http(HttpMethod.GET, "/api/v1/auth/me", new HttpEntity<>(seedHeaders));
		assertEquals(HttpStatus.OK, seed.getStatusCode(), "seeded session must authenticate /auth/me");

		SetCookie session = findSetCookie(seed.getHeaders(), "POSDOCSESSION");
		assertNotNull(session, "seeding must set the POSDOCSESSION session cookie");
		assertTrue(session.value() != null && !session.value().isBlank(),
				"POSDOCSESSION must carry a real session id");
		SetCookie xsrf = findSetCookie(seed.getHeaders(), "XSRF-TOKEN");
		assertNotNull(xsrf, "authenticated /auth/me must emit the XSRF-TOKEN cookie");

		// 2. The seeded session authenticates a follow-up request (proving the
		//    session is genuinely authenticated, not just present).
		ResponseEntity<String> me = http(HttpMethod.GET, "/api/v1/auth/me",
				new HttpEntity<>(headersWith("POSDOCSESSION=" + session.value())));
		assertEquals(HttpStatus.OK, me.getStatusCode(), "seeded session must stay authenticated");

		// 3. Logout with the real session cookie and the exact CSRF token.
		HttpHeaders outHeaders = new HttpHeaders();
		outHeaders.set("Cookie", "POSDOCSESSION=" + session.value() + "; XSRF-TOKEN=" + xsrf.value());
		outHeaders.set("X-XSRF-TOKEN", xsrf.value());
		ResponseEntity<String> out = http(HttpMethod.POST, "/api/v1/auth/logout", new HttpEntity<>(outHeaders));
		assertEquals(HttpStatus.NO_CONTENT, out.getStatusCode(), "logout must return 204");

		// 4. The real container + explicit cleanup expired the renamed session cookie.
		SetCookie expired = findSetCookie(out.getHeaders(), "POSDOCSESSION");
		assertNotNull(expired, "logout must expire the POSDOCSESSION cookie");
		assertEquals("", expired.value(), "POSDOCSESSION must be cleared (empty value)");
		assertTrue(expired.maxAge() != null && expired.maxAge() == 0,
				"POSDOCSESSION must be expired with Max-Age=0, raw: " + raw(out.getHeaders(), "POSDOCSESSION"));
	}

	private ResponseEntity<String> http(HttpMethod method, String path, HttpEntity<?> entity) {
		return this.rest.exchange("http://localhost:" + this.port + path, method, entity, String.class);
	}

	private static HttpHeaders headersWith(String cookie) {
		HttpHeaders headers = new HttpHeaders();
		headers.set("Cookie", cookie);
		return headers;
	}

	private static SetCookie findSetCookie(HttpHeaders headers, String name) {
		String raw = raw(headers, name);
		return raw == null ? null : parseSetCookie(raw);
	}

	private static String raw(HttpHeaders headers, String name) {
		List<String> cookies = headers.get("Set-Cookie");
		if (cookies == null) {
			return null;
		}
		String lowerName = name.toLowerCase(java.util.Locale.ROOT);
		for (String cookie : cookies) {
			if (cookie != null && cookie.toLowerCase(java.util.Locale.ROOT).startsWith(lowerName + "=")) {
				return cookie;
			}
		}
		return null;
	}

	private static SetCookie parseSetCookie(String cookie) {
		String[] parts = cookie.split(";", -1);
		String nameValue = parts[0].trim();
		int eq = nameValue.indexOf('=');
		String value = eq >= 0 ? nameValue.substring(eq + 1).trim() : "";
		Integer maxAge = null;
		for (String attribute : parts) {
			String trimmed = attribute.trim();
			if (trimmed.toLowerCase(java.util.Locale.ROOT).startsWith("max-age=")) {
				maxAge = Integer.parseInt(trimmed.substring("max-age=".length()).trim());
			}
		}
		return new SetCookie(value, maxAge);
	}

	/** A parsed {@code Set-Cookie} value: the cookie value and its {@code Max-Age}. */
	private record SetCookie(String value, Integer maxAge) {
	}

	/**
	 * Test-only configuration that seeds an authenticated session so a real Google
	 * login is not required to exercise the logout flow on the real container.
	 */
	@TestConfiguration
	static class SeedSessionConfiguration {

		@Bean
		FilterRegistrationBean<Filter> testSessionSeedFilter() {
			FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>();
			registration.setFilter((request, response, chain) -> {
				HttpServletRequest httpRequest = (HttpServletRequest) request;
				String subject = httpRequest.getHeader("X-Test-Seed-Auth");
				if (subject != null && !subject.isBlank()) {
					boolean reviewer = "reviewer".equalsIgnoreCase(httpRequest.getHeader("X-Test-Seed-Role"));
					HttpSession session = httpRequest.getSession(true);
					OidcUser user = OidcTestAuth.syntheticOidcUser(subject, reviewer);
					OAuth2AuthenticationToken token =
							new OAuth2AuthenticationToken(user, user.getAuthorities(), "google");
					SecurityContext context = SecurityContextHolder.createEmptyContext();
					context.setAuthentication(token);
					session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
				}
				chain.doFilter(request, response);
			});
			registration.addUrlPatterns("/*");
			// Run before the Spring Security filter chain (order -100) so the seeded
			// session is already present when the security context is loaded.
			registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
			return registration;
		}
	}

}
