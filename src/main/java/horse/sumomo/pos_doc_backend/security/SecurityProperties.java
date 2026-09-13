package horse.sumomo.pos_doc_backend.security;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Validated configuration for the {@code app.security} group.
 *
 * <p>Binding is fail-closed: an invalid {@code mode}, an empty Google allowlist,
 * a malformed or open-redirect {@code post-login-redirect}, an invalid CORS
 * origin, or a missing/short {@code stack-test-token} (in stack-test mode) all
 * throw at startup. No real value or usable default appears in any tracked file.
 *
 * <p>Subjects are normalized by trimming ASCII surrounding whitespace and
 * removing empty elements; duplicates (within a list or across the viewer and
 * reviewer lists) are rejected.
 */
@ConfigurationProperties(prefix = "app.security")
public final class SecurityProperties {

	private final String mode;
	private final Set<String> viewerSubjects;
	private final Set<String> reviewerSubjects;
	private final List<String> allowedOrigins;
	private final String postLoginRedirect;
	private final String stackTestToken;

	public SecurityProperties(String mode, Google google, List<String> allowedOrigins, String postLoginRedirect,
			String stackTestToken) {
		String m = (mode == null || mode.isBlank()) ? "google" : mode.trim().toLowerCase(Locale.ROOT);
		if (!"google".equals(m) && !"stack-test".equals(m)) {
			throw new IllegalArgumentException("app.security.mode must be 'google' or 'stack-test'");
		}
		this.mode = m;

		List<String> rawViewer = (google == null || google.viewerSubjects() == null) ? List.of() : google.viewerSubjects();
		List<String> rawReviewer = (google == null || google.reviewerSubjects() == null) ? List.of() : google.reviewerSubjects();
		Set<String> viewer = normalizeSubjects(rawViewer);
		Set<String> reviewer = normalizeSubjects(rawReviewer);
		Set<String> overlap = new HashSet<>(viewer);
		overlap.retainAll(reviewer);
		if (!overlap.isEmpty()) {
			throw new IllegalArgumentException("a subject appears in both the viewer and reviewer lists");
		}
		this.viewerSubjects = Set.copyOf(viewer);
		this.reviewerSubjects = Set.copyOf(reviewer);

		String redirect = (postLoginRedirect == null || postLoginRedirect.isBlank()) ? "/" : postLoginRedirect;
		validatePostLoginRedirect(redirect);
		this.postLoginRedirect = redirect;

		List<String> rawOrigins = (allowedOrigins == null) ? List.of() : allowedOrigins;
		List<String> origins = new ArrayList<>(rawOrigins.size());
		for (String origin : rawOrigins) {
			origins.add(validateOrigin(origin));
		}
		this.allowedOrigins = List.copyOf(origins);

		this.stackTestToken = stackTestToken;

		if ("google".equals(this.mode)) {
			if (viewer.isEmpty() && reviewer.isEmpty()) {
				throw new IllegalArgumentException("google mode requires at least one allowed subject");
			}
			if (stackTestToken != null && !stackTestToken.isBlank()) {
				throw new IllegalArgumentException("app.security.stack-test-token is not allowed in google mode");
			}
		}
		else {
			if (stackTestToken == null || !stackTestToken.matches("[0-9a-fA-F]{64,}")) {
				throw new IllegalArgumentException(
						"stack-test mode requires a stack-test-token of at least 64 hexadecimal characters");
			}
		}
	}

	private static Set<String> normalizeSubjects(List<String> raw) {
		Set<String> seen = new LinkedHashSet<>();
		for (String value : raw) {
			if (value == null) {
				continue;
			}
			String trimmed = value.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			if (!seen.add(trimmed)) {
				throw new IllegalArgumentException("duplicate subject in the security allowlist");
			}
		}
		return seen;
	}

	private static void validatePostLoginRedirect(String redirect) {
		if (!redirect.startsWith("/") || redirect.startsWith("//")) {
			throw new IllegalArgumentException(
					"app.security.post-login-redirect must be a same-origin relative path starting with a single '/'");
		}
		if (redirect.indexOf(':') >= 0 || redirect.contains("@") || redirect.contains("?") || redirect.contains("#")) {
			throw new IllegalArgumentException(
					"app.security.post-login-redirect must not contain a scheme, host, query, or fragment");
		}
	}

	private static String validateOrigin(String raw) {
		String origin = raw == null ? "" : raw.trim();
		if (origin.isEmpty() || origin.contains("*")) {
			throw new IllegalArgumentException("app.security.allowed-origins entries must be exact origins (no wildcards)");
		}
		URI uri;
		try {
			uri = URI.create(origin);
		}
		catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("app.security.allowed-origins entry is not a valid origin");
		}
		String scheme = uri.getScheme();
		String host = uri.getHost();
		if (scheme == null || host == null
				|| !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
			throw new IllegalArgumentException("app.security.allowed-origins entry must be an http(s) origin");
		}
		if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
			throw new IllegalArgumentException("app.security.allowed-origins entry must not contain user-info, query, or fragment");
		}
		String path = uri.getPath();
		if (path != null && !path.isEmpty() && !path.equals("/")) {
			throw new IllegalArgumentException("app.security.allowed-origins entry must not contain a path");
		}
		String lowerHost = host.toLowerCase(Locale.ROOT);
		boolean localhostHttp = scheme.equalsIgnoreCase("http")
				&& (lowerHost.equals("localhost") || lowerHost.equals("127.0.0.1"));
		if (scheme.equalsIgnoreCase("http") && !localhostHttp) {
			throw new IllegalArgumentException(
					"app.security.allowed-origins must be https except http://localhost and http://127.0.0.1");
		}
		return origin;
	}

	public String mode() {
		return this.mode;
	}

	public boolean isStackTest() {
		return "stack-test".equals(this.mode);
	}

	public boolean isGoogle() {
		return "google".equals(this.mode);
	}

	public Set<String> viewerSubjects() {
		return this.viewerSubjects;
	}

	public Set<String> reviewerSubjects() {
		return this.reviewerSubjects;
	}

	public List<String> allowedOrigins() {
		return this.allowedOrigins;
	}

	public String postLoginRedirect() {
		return this.postLoginRedirect;
	}

	public String stackTestToken() {
		return this.stackTestToken;
	}

	/**
	 * Nested {@code app.security.google} group holding the viewer and reviewer
	 * subject allowlists.
	 */
	public record Google(List<String> viewerSubjects, List<String> reviewerSubjects) {
	}

}
