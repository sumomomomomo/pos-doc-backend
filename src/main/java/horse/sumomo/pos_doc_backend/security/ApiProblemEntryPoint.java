package horse.sumomo.pos_doc_backend.security;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Returns a sanitized JSON {@code 401 AUTHENTICATION_REQUIRED} problem for API
 * requests that lack a valid session. Never redirects to Google's login page; the
 * only login-start action is an explicit browser navigation to the OAuth2
 * authorization endpoint.
 */
@Component
public class ApiProblemEntryPoint implements AuthenticationEntryPoint {

	public static final String CODE = "AUTHENTICATION_REQUIRED";

	private final ProblemResponseWriter writer;

	public ApiProblemEntryPoint(ProblemResponseWriter writer) {
		this.writer = writer;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException authException) throws IOException {
		this.writer.write(response, HttpStatus.UNAUTHORIZED.value(), CODE, "Authentication is required.");
	}

}
