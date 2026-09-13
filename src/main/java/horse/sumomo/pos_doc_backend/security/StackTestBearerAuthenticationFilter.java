package horse.sumomo.pos_doc_backend.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Isolated, fail-closed authentication for the whole-stack verification.
 *
 * <p>A request with {@code Authorization: Bearer <token>} where the token matches the
 * configured 64+ hex stack-test token (compared in constant time) is authenticated
 * with a synthetic, PII-free principal carrying {@code ROLE_USER} and
 * {@code ROLE_REVIEWER}. Any missing or incorrect token leaves the request
 * unauthenticated. This filter is stateless; it is only ever constructed in
 * stack-test mode and must never be used to authorize a real user.
 */
public class StackTestBearerAuthenticationFilter extends OncePerRequestFilter {

	private final byte[] expectedToken;

	public StackTestBearerAuthenticationFilter(String expectedToken) {
		if (expectedToken == null || expectedToken.isBlank()) {
			throw new IllegalArgumentException("stack-test token must not be blank");
		}
		this.expectedToken = expectedToken.getBytes(StandardCharsets.UTF_8);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header != null && header.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
			String provided = header.substring("Bearer ".length()).trim();
			if (constantTimeEquals(provided)) {
				SecurityPrincipal principal = new SecurityPrincipal("stack-test:principal",
						"stack-test-principal@example.invalid", "Stack Test Principal");
				List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"),
						new SimpleGrantedAuthority("ROLE_REVIEWER"));
				UsernamePasswordAuthenticationToken authentication =
						new UsernamePasswordAuthenticationToken(principal, null, authorities);
				SecurityContextHolder.getContext().setAuthentication(authentication);
			}
		}
		filterChain.doFilter(request, response);
	}

	private boolean constantTimeEquals(String provided) {
		return MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), this.expectedToken);
	}

}
