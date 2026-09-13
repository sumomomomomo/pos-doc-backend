package horse.sumomo.pos_doc_backend.security;

import org.springframework.core.env.Environment;

/**
 * Fail-closed startup validation for the authentication boundary.
 *
 * <p>In Google mode the application refuses to start unless the standard
 * environment-backed Google credentials are present and not placeholders. In
 * stack-test mode it refuses to start unless the matching {@code stack-test} Spring
 * profile is active (the mode alone must never activate the test authentication).
 *
 * <p>This only reads the environment; it never logs credential or subject values.
 */
public final class SecurityStartupValidator {

	private final SecurityProperties properties;
	private final Environment environment;

	public SecurityStartupValidator(SecurityProperties properties, Environment environment) {
		this.properties = properties;
		this.environment = environment;
	}

	public void validate() {
		if (this.properties.isStackTest()) {
			boolean profileActive = false;
			for (String profile : this.environment.getActiveProfiles()) {
				if ("stack-test".equals(profile)) {
					profileActive = true;
					break;
				}
			}
			if (!profileActive) {
				throw new IllegalStateException(
						"app.security.mode=stack-test requires the 'stack-test' Spring profile to be active");
			}
			return;
		}
		// google mode
		String clientId = this.environment.getProperty("GOOGLE_CLIENT_ID");
		String clientSecret = this.environment.getProperty("GOOGLE_CLIENT_SECRET");
		if (isUsable(clientId) == false || isUsable(clientSecret) == false) {
			throw new IllegalStateException(
					"google mode requires usable GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET values");
		}
	}

	private static boolean isUsable(String value) {
		if (value == null || value.isBlank()) {
			return false;
		}
		String lower = value.toLowerCase();
		return !lower.contains("change-me") && !lower.contains("placeholder") && !lower.contains("unset")
				&& !lower.equals("your-client-id") && !lower.equals("your-client-secret")
				&& !lower.equals("example") && !lower.contains("xxxx");
	}

}
