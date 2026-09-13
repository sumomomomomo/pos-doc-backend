package horse.sumomo.pos_doc_backend.security;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Handles authorization denials (invoked by the {@code ExceptionTranslationFilter}
 * for an authenticated user without the required role) as a sanitized JSON
 * {@code 403 ACCESS_DENIED} problem.
 */
@Component
public class AuthorizationAccessDeniedHandler implements AccessDeniedHandler {

	public static final String CODE = "ACCESS_DENIED";

	private final ProblemResponseWriter writer;

	public AuthorizationAccessDeniedHandler(ProblemResponseWriter writer) {
		this.writer = writer;
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response,
			AccessDeniedException accessDeniedException) throws IOException {
		this.writer.write(response, HttpStatus.FORBIDDEN.value(), CODE, "You are not permitted to perform this operation.");
	}

}
