package horse.sumomo.pos_doc_backend.security;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DeferredCsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.yourcompany.pos.api.AuthenticationApi;
import com.yourcompany.pos.api.model.CurrentUser;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Implements the generated {@link AuthenticationApi} contract.
 *
 * <p>{@code GET /auth/me} returns the authenticated current user and materializes
 * the deferred CSRF token so the {@code XSRF-TOKEN} cookie is present and carries
 * the same value the client must submit in the {@code X-XSRF-TOKEN} header; the
 * response is never cached. {@code POST /auth/logout} is normally consumed by the
 * Spring Security logout filter (which invalidates the session and returns 204);
 * this method is retained as a compile-time contract fallback.
 */
@Controller
public class AuthenticationController implements AuthenticationApi {

	private final CurrentUserService currentUserService;
	private final CookieCsrfTokenRepository csrfTokenRepository;

	public AuthenticationController(CurrentUserService currentUserService,
			CookieCsrfTokenRepository csrfTokenRepository) {
		this.currentUserService = currentUserService;
		this.csrfTokenRepository = csrfTokenRepository;
	}

	@Override
	@RequestMapping(value = AuthenticationApi.PATH_GET_CURRENT_USER, method = RequestMethod.GET,
			produces = "application/json")
	public ResponseEntity<CurrentUser> getCurrentUser() {
		materializeCsrfToken();
		return ResponseEntity.ok()
				.cacheControl(CacheControl.noStore())
				.body(this.currentUserService.currentUser());
	}

	@Override
	@RequestMapping(value = AuthenticationApi.PATH_LOGOUT_CURRENT_USER, method = RequestMethod.POST)
	public ResponseEntity<Void> logoutCurrentUser() {
		return ResponseEntity.noContent().build();
	}

	/**
	 * Forces the deferred CSRF token to be generated and saved to the response so the
	 * {@code XSRF-TOKEN} cookie is written (the established Spring Security SPA
	 * pattern). Without a CSRF token in the security context (for example in the
	 * isolated stack-test chain) this is a no-op.
	 */
	private void materializeCsrfToken() {
		RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
		if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
			return;
		}
		HttpServletRequest request = servletAttributes.getRequest();
		HttpServletResponse response = servletAttributes.getResponse();
		Object token = request.getAttribute(CsrfToken.class.getName());
		CsrfToken csrfToken = null;
		if (token instanceof DeferredCsrfToken deferred) {
			csrfToken = deferred.get();
		}
		else if (token instanceof CsrfToken existing) {
			csrfToken = existing;
		}
		if (csrfToken != null && response != null) {
			this.csrfTokenRepository.saveToken(csrfToken, request, response);
		}
	}

}
