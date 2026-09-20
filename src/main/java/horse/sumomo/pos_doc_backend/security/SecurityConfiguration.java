package horse.sumomo.pos_doc_backend.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseCookie;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

/**
 * Always-active security configuration: binds and exposes {@link SecurityProperties}
 * and runs the fail-closed {@link SecurityStartupValidator} at startup. The mode
 * specific filter chains are contributed by {@link GoogleSecurityConfiguration}
 * (google mode) and {@link StackTestSecurityConfiguration} (stack-test mode).
 */
@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfiguration {

	@Bean
	public SecurityStartupValidator securityStartupValidator(SecurityProperties properties, Environment environment) {
		SecurityStartupValidator validator = new SecurityStartupValidator(properties, environment);
		validator.validate();
		return validator;
	}

	/**
	 * Shared cookie-based CSRF token repository for the production (Google) chain.
	 *
	 * <p>Explicitly configured for the browser SPA contract: the cookie is named
	 * {@code XSRF-TOKEN}, the token is submitted in the {@code X-XSRF-TOKEN} header,
	 * the cookie is JavaScript-readable (HttpOnly=false) while the session cookie
	 * remains HttpOnly, the cookie path is {@code /}, the cookie is always Secure
	 * (production runs behind the HTTPS reverse proxy), and {@code SameSite=Lax}
	 * matches the session cookie. The raw token is stored in the cookie; the SPA
	 * request handler of the filter chain resolves the exact cookie value from the
	 * header.
	 */
	@Bean
	public CookieCsrfTokenRepository cookieCsrfTokenRepository() {
		CookieCsrfTokenRepository repository = new CookieCsrfTokenRepository();
		repository.setCookieName("XSRF-TOKEN");
		repository.setHeaderName("X-XSRF-TOKEN");
		repository.setCookiePath("/");
		repository.setCookieCustomizer((ResponseCookie.ResponseCookieBuilder builder) -> builder
				.httpOnly(false)
				.secure(true)
				.sameSite("Lax"));
		return repository;
	}

}
