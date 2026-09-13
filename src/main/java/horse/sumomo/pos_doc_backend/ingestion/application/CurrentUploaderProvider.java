package horse.sumomo.pos_doc_backend.ingestion.application;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

import horse.sumomo.pos_doc_backend.security.AuthRequiredException;
import horse.sumomo.pos_doc_backend.security.SecurityPrincipal;

/**
 * Supplies the uploader subject for new POS records from the authenticated
 * principal in the {@link SecurityContextHolder}.
 *
 * <p>Google mode returns {@code google:<sub>} using the already-validated stable
 * subject. Stack-test mode returns the synthetic principal name (prefixed
 * {@code stack-test:} if not already namespaced). The value is never taken from a
 * request header or parameter, and never from an email or display name. A missing,
 * anonymous, or non-OIDC principal fails closed with a sanitized
 * {@link AuthRequiredException}.
 *
 * <p>Background RabbitMQ consumers have no HTTP {@link SecurityContext} and must
 * never call this provider; only the initial HTTP intake path does.
 */
@Component
public class CurrentUploaderProvider {

	private static final String STACK_TEST_NAMESPACE = "stack-test:";

	public String currentUploader() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !authentication.isAuthenticated()
				|| authentication.getPrincipal() instanceof String) {
			throw new AuthRequiredException();
		}
		if (authentication.getPrincipal() instanceof OidcUser oidcUser) {
			String subject = oidcUser.getSubject();
			if (subject == null || subject.isBlank()) {
				throw new AuthRequiredException();
			}
			return "google:" + subject;
		}
		if (authentication.getPrincipal() instanceof SecurityPrincipal principal) {
			String name = principal.name();
			if (name == null || name.isBlank()) {
				throw new AuthRequiredException();
			}
			return name.startsWith(STACK_TEST_NAMESPACE) ? name : STACK_TEST_NAMESPACE + name;
		}
		throw new AuthRequiredException();
	}

}
