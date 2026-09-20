package horse.sumomo.pos_doc_backend.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import horse.sumomo.pos_doc_backend.content.DocumentContentService;
import horse.sumomo.pos_doc_backend.ingestion.application.PosArchiveIntakeService;
import horse.sumomo.pos_doc_backend.ingestion.application.PosDocumentListService;
import horse.sumomo.pos_doc_backend.review.IngestionJobReadService;
import horse.sumomo.pos_doc_backend.review.PosRecordCommandService;
import horse.sumomo.pos_doc_backend.review.PosRecordReadService;
import horse.sumomo.pos_doc_backend.review.PosRecordSearchService;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CORS boundary tests with an explicitly configured allowed origin.
 *
 * <p>Runs in a fresh context (via {@link DirtiesContext.ClassMode#BEFORE_CLASS})
 * with a single exact allowed origin so the positive preflight and the
 * unconfigured-origin negative case can be proven against the real filter chain.
 * The empty-origins case (no CORS headers at all) is proven in
 * {@link GoogleSecurityFilterChainTest}, whose context leaves the list empty.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class CorsConfigurationTest {

	private static final String ALLOWED_ORIGIN = "https://spa.example.com";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private PosArchiveIntakeService intakeService;
	@MockitoBean
	private PosDocumentListService documentListService;
	@MockitoBean
	private PosRecordReadService readService;
	@MockitoBean
	private PosRecordSearchService searchService;
	@MockitoBean
	private PosRecordCommandService commandService;
	@MockitoBean
	private IngestionJobReadService ingestionJobReadService;
	@MockitoBean
	private DocumentContentService contentService;

	@DynamicPropertySource
	static void allowedOrigin(DynamicPropertyRegistry registry) {
		registry.add("app.security.allowed-origins", () -> ALLOWED_ORIGIN);
	}

	// An allowed-origin preflight returns the exact configured origin (never *),
	// credentials, and the expected methods and headers.
	@Test
	void allowedOriginPreflightReturnsExactOriginAndPermissions() throws Exception {
		this.mockMvc.perform(options("/auth/me")
				.header("Origin", ALLOWED_ORIGIN)
				.header("Access-Control-Request-Method", "POST")
				.header("Access-Control-Request-Headers", "Content-Type, X-XSRF-TOKEN"))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN))
				.andExpect(header().string("Access-Control-Allow-Credentials", "true"))
				.andExpect(result -> {
					String methods = result.getResponse().getHeader("Access-Control-Allow-Methods");
					assertTrue(methods != null && methods.contains("POST"),
							"allow-methods must include POST, got: " + methods);
					String headers = result.getResponse().getHeader("Access-Control-Allow-Headers");
					assertTrue(headers != null && headers.contains("X-XSRF-TOKEN"),
							"allow-headers must include X-XSRF-TOKEN, got: " + headers);
				});
	}

	// An origin that is not configured receives no CORS permission headers.
	@Test
	void unconfiguredOriginReceivesNoCorsPermissionHeaders() throws Exception {
		this.mockMvc.perform(options("/auth/me")
				.header("Origin", "https://evil.example.com")
				.header("Access-Control-Request-Method", "POST"))
				.andExpect(result -> {
					String acao = result.getResponse().getHeader("Access-Control-Allow-Origin");
					assertTrue(acao == null,
							"no Access-Control-Allow-Origin for an unconfigured origin, got: " + acao);
					assertTrue(result.getResponse().getHeader("Access-Control-Allow-Credentials") == null,
							"no Access-Control-Allow-Credentials for an unconfigured origin");
				});
	}

}
