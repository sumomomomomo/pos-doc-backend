package horse.sumomo.pos_doc_backend.security;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the main untested boundary between the Nginx reverse proxy (the
 * {@code sumomo-blog} repository) and this backend.
 *
 * <p>In production the browser calls {@code https://sumomo.horse/api/v1/...} and Nginx
 * proxies that internally to {@code 192.168.1.35:18080}, forwarding the public host and
 * HTTPS scheme in the {@code X-Forwarded-*} headers. The backend is configured with
 * {@code server.forward-headers-strategy=framework}, so Spring's
 * {@code ForwardedHeaderFilter} must rewrite the request's scheme/host/port from those
 * headers. Concretely, the Google OAuth2 authorization redirect (a 3xx to
 * {@code accounts.google.com}) must carry the <em>public</em> callback
 * {@code https://sumomo.horse/api/v1/login/oauth2/code/google}, not the internal
 * {@code http://192.168.1.35:18080/...} address — otherwise Google would reject the
 * callback against the registered redirect URI.
 *
 * <p>This runs against a real embedded servlet container (not MockMvc) so the actual
 * {@code ForwardedHeaderFilter} and the OAuth2 redirect filter are exercised end to end.
 * The synthetic Google credentials and subject allowlists are supplied by the surefire
 * configuration in {@code pom.xml}, so no real Google account is contacted; the test only
 * inspects the generated authorization redirect.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OauthForwardedHeaderTest {

	private static final String PUBLIC_CALLBACK = "https://sumomo.horse/api/v1/login/oauth2/code/google";

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void sqlite(DynamicPropertyRegistry registry) throws Exception {
		Path dbFile = Files.createTempFile("pos-doc-oauth-fwd-test", ".db");
		dbFile.toFile().deleteOnExit();
		Path.of(dbFile.toString() + "-wal").toFile().deleteOnExit();
		Path.of(dbFile.toString() + "-shm").toFile().deleteOnExit();
		registry.add("SQLITE_URL", () -> "jdbc:sqlite:" + dbFile);
	}

	@Test
	void googleAuthorizationRedirectUsesTheForwardedPublicCallbackUrl() throws Exception {
		String redirectUri = redirectUri(authorizationRequest(true));
		assertNotNull(redirectUri, "the Google authorization request must include a redirect_uri");
		assertEquals(PUBLIC_CALLBACK, redirectUri,
				"redirect_uri must be the public callback URL from the X-Forwarded-* headers, not an internal address");
	}

	@Test
	void withoutForwardedHeadersTheRedirectUsesTheInternalAddress() throws Exception {
		// Negative control: without the forwarded headers the same endpoint must use the
		// real (internal) server address. This proves the forwarded-header test above is
		// meaningful and not passing trivially.
		String redirectUri = redirectUri(authorizationRequest(false));
		assertNotNull(redirectUri, "the Google authorization request must include a redirect_uri");
		assertEquals("http://localhost:" + this.port + "/api/v1/login/oauth2/code/google", redirectUri,
				"without X-Forwarded-* headers the redirect_uri must be the internal server address");
		assertFalse(redirectUri.contains("sumomo.horse"),
				"without forwarded headers the public host must not leak into the redirect_uri");
	}

	/**
	 * Issues {@code GET /api/v1/oauth2/authorization/google} (optionally with the Nginx
	 * forwarded headers) and returns the 3xx {@code redirect_uri} from the generated
	 * Google authorization request.
	 */
	private String redirectUri(HttpResponse<String> response) {
		int status = response.statusCode();
		assertTrue(status >= 300 && status < 400,
				"GET /oauth2/authorization/google must 3xx-redirect to Google, got " + status);
		String location = response.headers().firstValue("Location").orElse(null);
		assertNotNull(location, "the authorization redirect must carry a Location header");
		String redirectUri = queryParam(location, "redirect_uri");
		assertNotNull(redirectUri, "the Google authorization request must include redirect_uri; Location=" + location);
		return redirectUri;
	}

	private HttpResponse<String> authorizationRequest(boolean withForwardedHeaders) throws Exception {
		HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + this.port + "/api/v1/oauth2/authorization/google"))
				.GET();
		if (withForwardedHeaders) {
			// The public Host is carried by X-Forwarded-Host (the framework
			// ForwardedHeaderFilter reads the X-Forwarded-* headers, not the wire Host,
			// which a Java HTTP client cannot override to a non-local value).
			builder.header("X-Forwarded-Host", "sumomo.horse");
			builder.header("X-Forwarded-Proto", "https");
			builder.header("X-Forwarded-Port", "443");
		}
		return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

	/** Extracts and URL-decodes a single query parameter from a URL. */
	private static String queryParam(String url, String name) {
		URI uri = URI.create(url);
		String query = uri.getRawQuery();
		if (query == null) {
			return null;
		}
		for (String pair : query.split("&")) {
			int eq = pair.indexOf('=');
			String key = eq >= 0 ? pair.substring(0, eq) : pair;
			if (name.equals(key)) {
				String value = eq >= 0 ? pair.substring(eq + 1) : "";
				return URLDecoder.decode(value, StandardCharsets.UTF_8);
			}
		}
		return null;
	}
}
