package horse.sumomo.pos_doc_backend.security;

import java.util.List;
import java.util.Locale;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
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

	private static final String CSRF_COOKIE_NAME = "XSRF-TOKEN";

	@Bean
	public OidcSubjectAuthorizer oidcSubjectAuthorizer(SecurityProperties properties) {
		return new OidcSubjectAuthorizer(properties.viewerSubjects(), properties.reviewerSubjects(),
				OidcSubjectAuthorizer.GOOGLE_ISSUER);
	}

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityProperties properties,
			OidcSubjectAuthorizer authorizer, ApiProblemEntryPoint entryPoint, CsrfAccessDeniedHandler csrfHandler,
			AuthorizationAccessDeniedHandler authorizationHandler, CookieCsrfTokenRepository csrfTokenRepository)
			throws Exception {

		http
			.cors(cors -> cors.configurationSource(corsConfigurationSource(properties)))
			.csrf(csrf -> csrf
				.csrfTokenRepository(csrfTokenRepository)
				.requireCsrfProtectionMatcher(allButSearchPost()))
			.oauth2Login(oauth2 -> oauth2
				.userInfoEndpoint(info -> info.oidcUserService(allowlistingOidcUserService(authorizer)))
				.defaultSuccessUrl(properties.postLoginRedirect(), true))
			.logout(logout -> logout
				.logoutRequestMatcher(isPost("/auth/logout"))
				.logoutSuccessHandler((request, response, authentication) -> {
					Cookie csrfCookie = new Cookie(CSRF_COOKIE_NAME, "");
					csrfCookie.setPath("/");
					csrfCookie.setMaxAge(0);
					csrfCookie.setHttpOnly(true);
					response.addCookie(csrfCookie);
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
	 */
	private AuthorizationManager<RequestAuthorizationContext> authorizationManager() {
		return (authentication, context) -> new AuthorizationDecision(
				decide(appPath(context.getRequest()),
						context.getRequest().getMethod().toUpperCase(Locale.ROOT), authentication.get()));
	}

	private static boolean decide(String path, String method,
			org.springframework.security.core.Authentication authentication) {
		if (isPublic(path, method)) {
			return true;
		}
		if (method.equals("GET") && isProtectedContent(path)) {
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

	private static boolean isProtectedContent(String path) {
		return path.matches("/pos-records/[^/]+/documents/[^/]+/content")
				|| path.matches("/pos-records/[^/]+/source-archive/content");
	}

	private static boolean isWrite(String path, String method) {
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
