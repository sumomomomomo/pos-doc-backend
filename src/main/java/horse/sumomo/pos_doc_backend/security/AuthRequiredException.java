package horse.sumomo.pos_doc_backend.security;

/**
 * Stable, sanitized authentication failure for service-layer code that needs an
 * authenticated principal (the current-user service and the uploader provider).
 *
 * <p>This is defense in depth: the Spring Security filter chain normally rejects
 * unauthenticated API requests before they reach the service. It is thrown when a
 * service is invoked without a valid authenticated principal (for example from a
 * background context or a misconfigured request). It maps to a sanitized
 * {@code 401 AUTHENTICATION_REQUIRED} problem and never carries identity details.
 */
public class AuthRequiredException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public AuthRequiredException() {
		super("Authentication is required.");
	}

}
