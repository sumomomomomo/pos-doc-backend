package horse.sumomo.pos_doc_backend.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;

/**
 * Maps an already-validated OpenID Connect ID token to the application's granted
 * authorities based on a stable-subject allowlist.
 *
 * <p>This is the single place where the {@code sub} claim is trusted. It never
 * authorizes from a display name, email, or Google group claim. Every failure
 * (wrong issuer, missing/blank {@code sub}, missing email, unverified email, or a
 * subject not in the allowlist) is rejected with an {@link OAuth2AuthenticationException}
 * carrying the stable, PII-free category {@link #DENIAL_CATEGORY}. The failure path
 * therefore cannot reveal whether a denied account was simply absent from a list.
 *
 * <p>A viewer receives only {@code ROLE_USER}; a reviewer receives both
 * {@code ROLE_USER} and {@code ROLE_REVIEWER}.
 */
public final class OidcSubjectAuthorizer {

	/** Stable internal category for any OIDC authorization failure. */
	public static final String DENIAL_CATEGORY = "user_not_allowed";

	/** The standard Google OpenID Connect issuer. */
	public static final String GOOGLE_ISSUER = "https://accounts.google.com";

	private static final GrantedAuthority ROLE_USER = new SimpleGrantedAuthority("ROLE_USER");
	private static final GrantedAuthority ROLE_REVIEWER = new SimpleGrantedAuthority("ROLE_REVIEWER");

	private final Set<String> viewerSubjects;
	private final Set<String> reviewerSubjects;
	private final String expectedIssuer;

	public OidcSubjectAuthorizer(Set<String> viewerSubjects, Set<String> reviewerSubjects, String expectedIssuer) {
		this.viewerSubjects = Set.copyOf(viewerSubjects);
		this.reviewerSubjects = Set.copyOf(reviewerSubjects);
		if (expectedIssuer == null || expectedIssuer.isBlank()) {
			throw new IllegalArgumentException("expectedIssuer must not be blank");
		}
		this.expectedIssuer = expectedIssuer;
	}

	/**
	 * Returns the authorities for the given ID token, or rejects the
	 * authentication with a stable, PII-free category.
	 */
	public List<GrantedAuthority> authoritiesFor(OidcIdToken idToken) {
		if (idToken == null) {
			reject();
		}
		java.net.URL issuer = idToken.getIssuer();
		if (issuer == null || !this.expectedIssuer.equals(issuer.toString())) {
			reject();
		}
		String subject = idToken.getSubject();
		if (subject == null || subject.isBlank()) {
			reject();
		}
		Object email = idToken.getClaim("email");
		if (email == null || email.toString().isBlank()) {
			reject();
		}
		if (!Boolean.TRUE.equals(idToken.getClaim("email_verified"))) {
			reject();
		}
		boolean reviewer = this.reviewerSubjects.contains(subject);
		if (!reviewer && !this.viewerSubjects.contains(subject)) {
			reject();
		}
		List<GrantedAuthority> authorities = new ArrayList<>(2);
		authorities.add(ROLE_USER);
		if (reviewer) {
			authorities.add(ROLE_REVIEWER);
		}
		return authorities;
	}

	private static void reject() {
		throw new OAuth2AuthenticationException(new OAuth2Error(DENIAL_CATEGORY, DENIAL_CATEGORY, null));
	}

}
