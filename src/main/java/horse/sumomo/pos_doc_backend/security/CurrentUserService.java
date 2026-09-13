package horse.sumomo.pos_doc_backend.security;

import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

import com.yourcompany.pos.api.model.AppRole;
import com.yourcompany.pos.api.model.CurrentUser;

/**
 * Reads the authenticated principal from the {@link SecurityContextHolder} and
 * maps only the verified email, display name, and application roles to the
 * {@link CurrentUser} DTO.
 *
 * <p>The Google {@code sub}, tokens, claim map, session id, and cookie value are
 * never exposed. If the {@code name} claim is absent, the verified email is used
 * as the display name (it is never logged). Identity is never taken from request
 * headers or parameters. A missing, anonymous, or non-OIDC principal fails closed
 * with {@link AuthRequiredException}.
 */
@Service
public class CurrentUserService {

	private static final String ROLE_USER = "ROLE_USER";
	private static final String ROLE_REVIEWER = "ROLE_REVIEWER";

	public CurrentUser currentUser() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !authentication.isAuthenticated()
				|| authentication.getPrincipal() instanceof String) {
			throw new AuthRequiredException();
		}
		String email;
		String displayName;
		if (authentication.getPrincipal() instanceof OidcUser oidcUser) {
			email = oidcUser.getEmail();
			Object name = oidcUser.getClaim("name");
			displayName = (name != null && !name.toString().isBlank()) ? name.toString() : email;
		}
		else if (authentication.getPrincipal() instanceof SecurityPrincipal principal) {
			email = principal.email();
			displayName = principal.displayName();
		}
		else {
			throw new AuthRequiredException();
		}
		if (email == null || email.isBlank()) {
			throw new AuthRequiredException();
		}
		Set<AppRole> roles = new LinkedHashSet<>();
		for (GrantedAuthority authority : authentication.getAuthorities()) {
			if (ROLE_USER.equals(authority.getAuthority())) {
				roles.add(AppRole.USER);
			}
			if (ROLE_REVIEWER.equals(authority.getAuthority())) {
				roles.add(AppRole.REVIEWER);
			}
		}
		if (roles.isEmpty()) {
			roles.add(AppRole.USER);
		}
		return new CurrentUser(email, displayName, roles);
	}

}
