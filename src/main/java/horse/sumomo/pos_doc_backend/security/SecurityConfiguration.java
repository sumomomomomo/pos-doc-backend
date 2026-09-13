package horse.sumomo.pos_doc_backend.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
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
	 * Shared cookie-based CSRF token repository. It is a top-level bean (not only a
	 * filter-chain-local object) so the {@code AuthenticationController} can
	 * materialize the current token and publish the {@code XSRF-TOKEN} cookie on
	 * {@code GET /auth/me}.
	 */
	@Bean
	public CookieCsrfTokenRepository cookieCsrfTokenRepository() {
		return CookieCsrfTokenRepository.withHttpOnlyFalse();
	}

}
