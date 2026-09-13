package horse.sumomo.pos_doc_backend.security;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

/**
 * Test support for authenticating MockMvc requests as a synthetic, already-validated
 * Google OIDC user (an {@link OidcUser}) carrying the application's granted roles.
 * The subject is chosen by the caller (typically one of the configured allowlist
 * subjects) and the token is synthetic; nothing ever contacts Google.
 *
 * <p>It delegates to Spring Security test support's {@code authentication(...)}
 * post-processor so the {@code SecurityContext} is published in the way the real
 * filter chain's {@code SecurityContextHolderFilter} expects.
 */
public final class OidcTestAuth {

	private OidcTestAuth() {
	}

	/**
	 * Authenticates the request as an allowed OIDC user with {@code ROLE_USER} (and
	 * {@code ROLE_REVIEWER} when {@code reviewer} is true).
	 */
	public static RequestPostProcessor oidc(String subject, boolean reviewer) {
		OidcUser user = syntheticOidcUser(subject, reviewer);
		return authentication(new OAuth2AuthenticationToken(user, user.getAuthorities(), "google"));
	}

	static OidcUser syntheticOidcUser(String subject, boolean reviewer) {
		List<GrantedAuthority> authorities = new ArrayList<>();
		authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
		if (reviewer) {
			authorities.add(new SimpleGrantedAuthority("ROLE_REVIEWER"));
		}
		OidcIdToken idToken = new OidcIdToken("test-id-token-value", Instant.now().minusSeconds(60),
				Instant.now().plusSeconds(300), Map.of("iss", OidcSubjectAuthorizer.GOOGLE_ISSUER, "sub", subject,
						"email", subject + "@example.test", "email_verified", true));
		return new DefaultOidcUser(authorities, idToken);
	}

}
