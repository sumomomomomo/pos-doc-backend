package horse.sumomo.pos_doc_backend.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Isolated stack-test security filter chain.
 *
 * <p>Activates only when {@code app.security.mode=stack-test} AND the
 * {@code stack-test} Spring profile are both present (enforced here and by
 * {@link SecurityStartupValidator}). The chain is stateless, disables CSRF (the
 * credential is an {@code Authorization} header, not a browser cookie), and
 * authenticates via {@link StackTestBearerAuthenticationFilter}. This mode is
 * exclusively for the portable whole-stack verifier and never appears in the
 * production Compose stack.
 */
@Configuration
@Profile("stack-test")
@ConditionalOnProperty(name = "app.security.mode", havingValue = "stack-test")
public class StackTestSecurityConfiguration {

	@Bean
	public SecurityFilterChain stackTestSecurityFilterChain(HttpSecurity http, SecurityProperties properties,
			ApiProblemEntryPoint entryPoint, AuthorizationAccessDeniedHandler authorizationHandler) throws Exception {

		StackTestBearerAuthenticationFilter bearerFilter =
				new StackTestBearerAuthenticationFilter(properties.stackTestToken());

		http
			.csrf(AbstractHttpConfigurer::disable)
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.addFilterBefore(bearerFilter, UsernamePasswordAuthenticationFilter.class)
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/actuator/health/**").permitAll()
				.requestMatchers(HttpMethod.GET, "/pos-records/**").authenticated()
				.requestMatchers(HttpMethod.POST, "/pos-records/search").authenticated()
				.requestMatchers(HttpMethod.GET, "/ingestion-jobs/**").authenticated()
				.requestMatchers(HttpMethod.GET, "/auth/me").authenticated()
				.requestMatchers(HttpMethod.POST, "/auth/logout").authenticated()
				.requestMatchers(HttpMethod.POST, "/pos-records").hasRole("REVIEWER")
				.requestMatchers(HttpMethod.PATCH, "/pos-records/**").hasRole("REVIEWER")
				.requestMatchers(HttpMethod.POST, "/pos-records/*/verification").hasRole("REVIEWER")
				.requestMatchers(HttpMethod.DELETE, "/pos-records/**").hasRole("REVIEWER")
				.anyRequest().authenticated())
			.exceptionHandling(ex -> ex
				.authenticationEntryPoint(entryPoint)
				.accessDeniedHandler(authorizationHandler));

		return http.build();
	}

}
