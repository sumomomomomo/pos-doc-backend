package horse.sumomo.pos_doc_backend.security;

import java.util.List;
import java.util.Locale;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Production (Google OIDC) security filter chain.
 *
 * <p>Browser-session authentication: Google authenticates the user, the principal is
 * stored in the server-side {@code HttpSession}, and the browser receives only the
 * opaque {@code POSDOCSESSION} cookie. The {@code sub}-based allowlist is enforced by
 * a custom {@link OidcUserService} that delegates token validation to Spring's decoder
 * and then applies {@link OidcSubjectAuthorizer}. API authentication/authorization
 * failures are sanitized JSON problems (never HTML redirects to Google).
 *
 * <p>Authorization rules are evaluated on the servlet path (the context path is not
 * part of the decision), which is independent of the configured {@code /api/v1}
 * context path.
 */
@Configuration
@ConditionalOnProperty(name = "app.security.mode", havingValue = "google", matchIfMissing = true)
public class GoogleSecurityConfiguration {

	@Bean
	public OidcSubjectAuthorizer oidcSubjectAuthorizer(SecurityProperties properties) {
		return new OidcSubjectAuthorizer(properties.viewerSubjects(), properties.reviewerSubjects(),
				OidcSubjectAuthorizer.GOOGLE_ISSUER);
	}

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityProperties properties,
			OidcSubjectAuthorizer authorizer, ApiProblemEntryPoint entryPoint, CsrfAccessDeniedHandler csrfHandler,
			AuthorizationAccessDeniedHandler authorizationHandler, CookieCsrfTokenRepository csrfTokenRepository,
			ServerProperties serverProperties)
			throws Exception {

		http
			.cors(cors -> cors.configurationSource(corsConfigurationSource(properties)))
			.csrf(csrf -> csrf
				// The supported Spring Security SPA flow: cookie-based repository plus a
				// request handler that resolves the exact cookie value from the
				// X-XSRF-TOKEN header (and keeps the BREACH-masking XOR path for
				// form-parameter submissions). The repository is the explicitly
				// configured bean (XSRF-TOKEN cookie, / path, Secure, SameSite=Lax,
				// HttpOnly=false).
				.spa()
				.csrfTokenRepository(csrfTokenRepository)
				.requireCsrfProtectionMatcher(allButSearchPost()))
			.oauth2Login(oauth2 -> oauth2
				.userInfoEndpoint(info -> info.oidcUserService(allowlistingOidcUserService(authorizer)))
				.successHandler(frontendSuccessHandler(properties.postLoginRedirect())))
			.logout(logout -> logout
				.logoutRequestMatcher(isPost("/auth/logout"))
				// The CsrfLogoutHandler added by the CSRF configurer clears the
				// XSRF-TOKEN cookie; the default SecurityContextLogoutHandler
				// invalidates the session server-side. The renamed session cookie
				// (POSDOCSESSION) is expired explicitly here, because the container
				// does not reliably emit the expiry cookie in the logout response.
				.logoutSuccessHandler((request, response, authentication) -> {
					clearSessionCookie(response, serverProperties.getServlet().getSession().getCookie());
					response.setStatus(HttpServletResponse.SC_NO_CONTENT);
				}))
			.exceptionHandling(ex -> ex
				.authenticationEntryPoint(entryPoint)
				.accessDeniedHandler(authorizationHandler))
			.authorizeHttpRequests(auth -> auth.anyRequest().access(authorizationManager()));

		SecurityFilterChain chain = http.build();
		for (Filter filter : chain.getFilters()) {
			if (filter instanceof CsrfFilter csrfFilter) {
				csrfFilter.setAccessDeniedHandler(csrfHandler);
			}
		}
		return chain;
	}

	/**
	 * The full authorization policy, evaluated on the servlet path and method.
	 * Reads require {@code ROLE_USER}; writes and protected content require
	 * {@code ROLE_REVIEWER}; the health check and OAuth2 login endpoints are public;
	 * everything else is denied.
	 *
	 * <p>Protected-content matching uses {@link PathPatternRequestMatcher}, which
	 * matches relative to the servlet context path and, like Spring MVC's own
	 * {@code PathPattern} routing, ignores matrix (semicolon) variables. This keeps
	 * the authorization decision aligned with Spring's {@code PathPattern} behavior
	 * regardless of how the container treats a {@code ;x=1} component, so a request
	 * that reaches the content controller is never downgraded to a plain read.
	 */
	private static final PathPatternRequestMatcher PDF_CONTENT =
			PathPatternRequestMatcher.pathPattern(HttpMethod.GET,
					"/pos-records/{posRecordId}/documents/{documentId}/content");
	private static final PathPatternRequestMatcher SOURCE_ARCHIVE_CONTENT =
			PathPatternRequestMatcher.pathPattern(HttpMethod.GET,
					"/pos-records/{posRecordId}/source-archive/content");

	private AuthorizationManager<RequestAuthorizationContext> authorizationManager() {
		return (authentication, context) -> new AuthorizationDecision(
				decide(context.getRequest(), authentication.get()));
	}

	/**
	 * Package-visible for direct regression testing of the authorization decision
	 * (including matrix-variable paths that the servlet stack rejects before they
	 * reach the controller).
	 */
	static boolean decide(HttpServletRequest request,
			org.springframework.security.core.Authentication authentication) {
		String path = appPath(request);
		String method = request.getMethod().toUpperCase(Locale.ROOT);
		if (isPublic(path, method)) {
			return true;
		}
		if (isProtectedContent(request)) {
			return hasRole(authentication, "ROLE_REVIEWER");
		}
		if (isWrite(path, method)) {
			return hasRole(authentication, "ROLE_REVIEWER");
		}
		if (isRead(path, method)) {
			return hasRole(authentication, "ROLE_USER");
		}
		return false;
	}

	private static boolean isPublic(String path, String method) {
		return path.startsWith("/actuator/health")
				|| path.startsWith("/oauth2/authorization/")
				|| path.startsWith("/login/oauth2/code/");
	}

	private static boolean isProtectedContent(HttpServletRequest request) {
		return PDF_CONTENT.matches(request) || SOURCE_ARCHIVE_CONTENT.matches(request);
	}

	private static boolean isWrite(String path, String method) {
		if (path.equals("/pos-records/search-page-archive") && method.equals("POST")) {
			return true;
		}
		if (path.equals("/pos-records") && method.equals("POST")) {
			return true;
		}
		if ((method.equals("PATCH") || method.equals("DELETE")) && path.startsWith("/pos-records/")) {
			return true;
		}
		return method.equals("POST") && path.matches("/pos-records/[^/]+/verification");
	}

	private static boolean isRead(String path, String method) {
		if (path.equals("/auth/me") || path.equals("/auth/logout")) {
			return method.equals("GET") || method.equals("POST");
		}
		if (path.equals("/pos-records/search") && method.equals("POST")) {
			return true;
		}
		if (method.equals("GET") && (path.startsWith("/pos-records") || path.startsWith("/ingestion-jobs"))) {
			return true;
		}
		return false;
	}

	private static boolean hasRole(org.springframework.security.core.Authentication authentication, String role) {
		if (authentication == null) {
			return false;
		}
		return authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority)
				.anyMatch(role::equals);
	}

	/**
	 * CSRF protection applies to the state-changing methods (as in Spring's default
	 * matcher), with POST /pos-records/search as the sole CSRF-exempt POST; every
	 * other state-changing method requires a valid CSRF token. Safe methods (GET,
	 * HEAD, OPTIONS, TRACE) never require a token.
	 */
	private static RequestMatcher allButSearchPost() {
		return request -> CsrfFilter.DEFAULT_CSRF_MATCHER.matches(request) && !isPost(request, "/pos-records/search");
	}

	private static RequestMatcher isPost(String path) {
		return request -> isPost(request, path);
	}

	private static boolean isPost(HttpServletRequest request, String path) {
		return "POST".equalsIgnoreCase(request.getMethod()) && path.equals(appPath(request));
	}

	/**
	 * Expires the configured session cookie so the browser deletes it on logout.
	 * The cookie is cleared by name with an empty value and {@code Max-Age=0},
	 * matching the configured path/domain/secure/same-site so the browser identifies
	 * the same cookie. This is explicit cookie cleanup: the server-side session is
	 * invalidated by {@code SecurityContextLogoutHandler}, but the renamed session
	 * cookie is not reliably expired by the container in the logout response. The
	 * header is written directly (rather than via {@code addCookie}) so the expiry
	 * is expressed as {@code Max-Age=0} regardless of the container's session-cookie
	 * serialization.
	 */
	private static void clearSessionCookie(HttpServletResponse response,
			org.springframework.boot.web.server.Cookie sessionCookie) {
		StringBuilder header = new StringBuilder();
		header.append(sessionCookie.getName()).append("=; Max-Age=0");
		header.append("; Path=").append(sessionCookie.getPath() != null ? sessionCookie.getPath() : "/");
		if (sessionCookie.getDomain() != null) {
			header.append("; Domain=").append(sessionCookie.getDomain());
		}
		if (Boolean.TRUE.equals(sessionCookie.getSecure())) {
			header.append("; Secure");
		}
		if (sessionCookie.getSameSite() != null) {
			header.append("; SameSite=").append(sessionCookie.getSameSite().name());
		}
		response.addHeader("Set-Cookie", header.toString());
	}

	/**
	 * The request path relative to the servlet context, which is what the
	 * authorization and CSRF rules are expressed against. In a real container the
	 * context path (for example {@code /api/v1}) is stripped from the request URI;
	 * in MockMvc the context path is not applied so the URI is already relative.
	 */
	private static String appPath(HttpServletRequest request) {
		String uri = request.getRequestURI();
		String contextPath = request.getContextPath();
		if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
			uri = uri.substring(contextPath.length());
		}
		return uri.isEmpty() ? "/" : uri;
	}

	/**
	 * Delegates token validation to Spring's default {@link OidcUserService}, then
	 * rebuilds the user with the allowlist-derived authorities. Any allowlist failure
	 * rejects the authentication with a stable, PII-free category.
	 */
	private static OAuth2UserService<OidcUserRequest, OidcUser> allowlistingOidcUserService(
			OidcSubjectAuthorizer authorizer) {
		OidcUserService delegate = new OidcUserService();
		return userRequest -> {
			OidcUser defaultUser = delegate.loadUser(userRequest);
			List<GrantedAuthority> authorities = authorizer.authoritiesFor(defaultUser.getIdToken());
			return new DefaultOidcUser(authorities, defaultUser.getIdToken(), defaultUser.getUserInfo());
		};
	}

	/**
	 * Success handler that redirects a freshly authenticated browser to the frontend
	 * subpage (for example {@code /pos/}).
	 *
	 * <p>The backend is served under the {@code /api/v1} servlet context path, so a
	 * plain {@code defaultSuccessUrl("/pos/", true)} would resolve the target against
	 * the context path and redirect to {@code /api/v1/pos/} — a path the backend does
	 * not host and therefore rejects with {@code 403}. The intended destination is the
	 * frontend route {@code /pos/} at the site root, outside the backend context path.
	 *
	 * <p>A {@link DefaultRedirectStrategy} with {@code contextRelative(true)} emits
	 * {@code Location: /pos/} (no context-path prefix). The default target URL is always
	 * used (never a saved request), so a crafted backend request cannot override the
	 * intended frontend destination — the same guarantee the previous
	 * {@code defaultSuccessUrl(target, true)} call provided.
	 *
	 * <p>Package-visible (not private) so the redirect target can be regression-tested
	 * directly against a request carrying the {@code /api/v1} context path.
	 */
	static AuthenticationSuccessHandler frontendSuccessHandler(String targetUrl) {
		DefaultRedirectStrategy redirectStrategy = new DefaultRedirectStrategy();
		redirectStrategy.setContextRelative(true);

		SimpleUrlAuthenticationSuccessHandler handler =
				new SimpleUrlAuthenticationSuccessHandler(targetUrl);
		handler.setAlwaysUseDefaultTargetUrl(true);
		handler.setRedirectStrategy(redirectStrategy);
		return handler;
	}

	private static CorsConfigurationSource corsConfigurationSource(SecurityProperties properties) {
		List<String> origins = properties.allowedOrigins();
		if (origins.isEmpty()) {
			return request -> null;
		}
		CorsConfiguration configuration = new CorsConfiguration();
		configuration.setAllowedOrigins(origins);
		configuration.setAllowedHeaders(List.of("Content-Type", "Accept", "X-XSRF-TOKEN"));
		configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
		configuration.setExposedHeaders(List.of("Location", "Content-Disposition"));
		configuration.setAllowCredentials(true);
		configuration.setMaxAge(1800L);
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", configuration);
		return source;
	}

}
