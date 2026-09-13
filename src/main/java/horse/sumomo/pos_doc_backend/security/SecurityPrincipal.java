package horse.sumomo.pos_doc_backend.security;

/**
 * A synthetic, PII-free principal used by the isolated stack-test authentication
 * mode. It carries a stable synthetic name, a non-routable email, and a synthetic
 * display name so that {@code /auth/me} and the uploader provider work without a
 * real Google subject, email, or name.
 */
public record SecurityPrincipal(String name, String email, String displayName) {

	@Override
	public String toString() {
		return "SecurityPrincipal[name=" + this.name + "]";
	}

}
