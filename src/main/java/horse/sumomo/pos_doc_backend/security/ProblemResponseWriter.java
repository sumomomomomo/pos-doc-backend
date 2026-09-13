package horse.sumomo.pos_doc_backend.security;

import java.io.IOException;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourcompany.pos.api.model.Problem;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Writes a sanitized {@link Problem} body with the {@code application/problem+json}
 * media type directly to the servlet response. Used by the authentication entry
 * point and the access-denied handlers, which run outside the {@code DispatcherServlet}
 * (so {@code @RestControllerAdvice} does not apply).
 *
 * <p>Only the stable {@code code}, a fixed PII-free {@code detail}, and the status are
 * written. The raw exception is never serialized. A dedicated {@link ObjectMapper}
 * is used (not the web-layer bean) so this component has no dependency on the web
 * auto-configuration.
 */
@Component
public class ProblemResponseWriter {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	public void write(HttpServletResponse response, int status, String code, String detail) throws IOException {
		if (response.isCommitted()) {
			return;
		}
		Problem problem = new Problem("about:blank", "Error", status).code(code).detail(detail);
		response.setStatus(status);
		response.setContentType("application/problem+json");
		response.setCharacterEncoding("UTF-8");
		OBJECT_MAPPER.writeValue(response.getWriter(), problem);
	}

}
