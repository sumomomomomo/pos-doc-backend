package horse.sumomo.pos_doc_backend.security;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Handles CSRF token rejections (invoked by the {@code CsrfFilter}'s own access
 * denied handler) as a sanitized JSON {@code 403 CSRF_TOKEN_INVALID} problem. This
 * is wired specifically to the CSRF filter so it is distinguishable from an
 * authorization denial.
 */
@Component
public class CsrfAccessDeniedHandler implements AccessDeniedHandler {

	public static final String CODE = "CSRF_TOKEN_INVALID";

	private final ProblemResponseWriter writer;

	public CsrfAccessDeniedHandler(ProblemResponseWriter writer) {
		this.writer = writer;
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response,
			org.springframework.security.access.AccessDeniedException accessDeniedException) throws IOException {
		this.writer.write(response, HttpStatus.FORBIDDEN.value(), CODE, "A valid CSRF token is required.");
	}

}
