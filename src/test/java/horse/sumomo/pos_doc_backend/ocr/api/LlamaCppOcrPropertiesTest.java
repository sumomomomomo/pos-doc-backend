package horse.sumomo.pos_doc_backend.ocr.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import horse.sumomo.pos_doc_backend.rendering.api.FirstPageRenderingProperties;

/**
 * Unit tests for {@link LlamaCppOcrProperties} validation rules.
 */
class LlamaCppOcrPropertiesTest {

	private static final String PRODUCTION_ORIGIN = "http://192.168.1.34:8080";
	private static final String PRODUCTION_PATH = "/v1/chat/completions";
	private static final String PRODUCTION_MODEL = "/models/dotsmocr-1.8b-q8_0.gguf";
	private static final Duration CONNECT = Duration.ofSeconds(5);
	private static final Duration READ = Duration.ofSeconds(300);
	private static final Duration CALL = Duration.ofSeconds(310);

	@Test
	void documentedDefaultsBindSuccessfully() {
		LlamaCppOcrProperties props = newProps(CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, 4096, 0.1, 0.9, 1);
		assertEquals(URI.create(PRODUCTION_ORIGIN), props.serverOrigin());
		assertEquals(PRODUCTION_PATH, props.chatCompletionsPath());
		assertEquals(PRODUCTION_MODEL, props.model());
		assertEquals(CONNECT, props.connectTimeout());
		assertEquals(READ, props.readTimeout());
		assertEquals(CALL, props.callTimeout());
		assertEquals(33554432L, props.maxImageBytes());
		assertEquals(2097152L, props.maxResponseBytes());
		assertEquals(1000000, props.maxOcrCharacters());
		assertEquals(4096, props.maxTokens());
		assertEquals(0.1, props.temperature(), 0.0001);
		assertEquals(0.9, props.topP(), 0.0001);
		assertEquals(1, props.maxConcurrentRequests());
	}

	@Test
	void productionServerOriginBindsExactly() {
		LlamaCppOcrProperties props = newProps(CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, 4096, 0.1, 0.9, 1);
		assertEquals("http", props.serverOrigin().getScheme());
		assertEquals("192.168.1.34", props.serverOrigin().getHost());
		assertEquals(8080, props.serverOrigin().getPort());
	}

	@Test
	void invalidSchemeIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new LlamaCppOcrProperties("ftp://192.168.1.34:8080", PRODUCTION_PATH, PRODUCTION_MODEL,
						CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, 4096, 0.1, 0.9, 1));
	}

	@Test
	void userInfoIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new LlamaCppOcrProperties("http://user:pass@192.168.1.34:8080", PRODUCTION_PATH,
						PRODUCTION_MODEL, CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, 4096, 0.1, 0.9, 1));
	}

	@Test
	void queryIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new LlamaCppOcrProperties("http://192.168.1.34:8080?debug=true", PRODUCTION_PATH,
						PRODUCTION_MODEL, CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, 4096, 0.1, 0.9, 1));
	}

	@Test
	void fragmentIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new LlamaCppOcrProperties("http://192.168.1.34:8080#frag", PRODUCTION_PATH, PRODUCTION_MODEL,
						CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, 4096, 0.1, 0.9, 1));
	}

	@Test
	void nonEmptyOriginPathIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new LlamaCppOcrProperties("http://192.168.1.34:8080/some/path", PRODUCTION_PATH,
						PRODUCTION_MODEL, CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, 4096, 0.1, 0.9, 1));
	}

	@Test
	void blankModelIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new LlamaCppOcrProperties(PRODUCTION_ORIGIN, PRODUCTION_PATH, "  ",
						CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, 4096, 0.1, 0.9, 1));
	}

	@Test
	void blankChatCompletionsPathIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new LlamaCppOcrProperties(PRODUCTION_ORIGIN, "  ", PRODUCTION_MODEL,
						CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, 4096, 0.1, 0.9, 1));
	}

	@Test
	void differentChatCompletionsPathIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new LlamaCppOcrProperties(PRODUCTION_ORIGIN, "/v1/completions", PRODUCTION_MODEL,
						CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, 4096, 0.1, 0.9, 1));
	}

	@Test
	void zeroConnectTimeoutIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new LlamaCppOcrProperties(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL,
						Duration.ZERO, READ, CALL, 33554432L, 2097152L, 1000000, 4096, 0.1, 0.9, 1));
	}

	@Test
	void negativeReadTimeoutIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new LlamaCppOcrProperties(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL,
						CONNECT, Duration.ofSeconds(-1), CALL, 33554432L, 2097152L, 1000000, 4096, 0.1, 0.9, 1));
	}

	@Test
	void zeroCallTimeoutIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new LlamaCppOcrProperties(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL,
						CONNECT, READ, Duration.ZERO, 33554432L, 2097152L, 1000000, 4096, 0.1, 0.9, 1));
	}

	@Test
	void connectTimeoutGreaterThanCallTimeoutIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new LlamaCppOcrProperties(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL,
						Duration.ofSeconds(310), READ, CALL, 33554432L, 2097152L, 1000000, 4096, 0.1, 0.9, 1));
	}

	@Test
	void connectTimeoutEqualToCallTimeoutIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new LlamaCppOcrProperties(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL,
						CALL, READ, CALL, 33554432L, 2097152L, 1000000, 4096, 0.1, 0.9, 1));
	}

	@Test
	void readTimeoutGreaterThanCallTimeoutIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new LlamaCppOcrProperties(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL,
						CONNECT, Duration.ofSeconds(311), CALL, 33554432L, 2097152L, 1000000, 4096, 0.1, 0.9, 1));
	}

	@Test
	void zeroMaxImageBytesIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new LlamaCppOcrProperties(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL,
						CONNECT, READ, CALL, 0L, 2097152L, 1000000, 4096, 0.1, 0.9, 1));
	}

	@Test
	void zeroMaxResponseBytesIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new LlamaCppOcrProperties(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL,
						CONNECT, READ, CALL, 33554432L, 0L, 1000000, 4096, 0.1, 0.9, 1));
	}

	@Test
	void zeroMaxOcrCharactersIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new LlamaCppOcrProperties(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL,
						CONNECT, READ, CALL, 33554432L, 2097152L, 0, 4096, 0.1, 0.9, 1));
	}

	@Test
	void maxImageBytesAboveRenderingPngLimitIsRejected() {
		LlamaCppOcrProperties props = newProps(CONNECT, READ, CALL, 33554433L, 2097152L, 1000000, 4096, 0.1, 0.9, 1);
		FirstPageRenderingProperties rendering = new FirstPageRenderingProperties(200, 52428800L, 5000, 5000,
				16000000L, 33554432L, 1);
		assertThrows(IllegalArgumentException.class,
				() -> props.validateAgainstRenderingLimit(rendering.maxPngBytes()));
	}

	@Test
	void maxImageBytesEqualToRenderingPngLimitIsAccepted() {
		LlamaCppOcrProperties props = newProps(CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, 4096, 0.1, 0.9, 1);
		FirstPageRenderingProperties rendering = new FirstPageRenderingProperties(200, 52428800L, 5000, 5000,
				16000000L, 33554432L, 1);
		props.validateAgainstRenderingLimit(rendering.maxPngBytes());
	}

	@Test
	void maxImageBytesBelowRenderingPngLimitIsAccepted() {
		LlamaCppOcrProperties props = newProps(CONNECT, READ, CALL, 33554431L, 2097152L, 1000000, 4096, 0.1, 0.9, 1);
		FirstPageRenderingProperties rendering = new FirstPageRenderingProperties(200, 52428800L, 5000, 5000,
				16000000L, 33554432L, 1);
		props.validateAgainstRenderingLimit(rendering.maxPngBytes());
	}

	@Test
	void maxTokensBelowOneIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new LlamaCppOcrProperties(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL,
						CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, 0, 0.1, 0.9, 1));
	}

	@Test
	void maxTokensAbove8192IsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new LlamaCppOcrProperties(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL,
						CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, 8193, 0.1, 0.9, 1));
	}

	@Test
	void temperatureBelowZeroIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new LlamaCppOcrProperties(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL,
						CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, 4096, -0.1, 0.9, 1));
	}

	@Test
	void temperatureAboveTwoIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new LlamaCppOcrProperties(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL,
						CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, 4096, 2.1, 0.9, 1));
	}

	@Test
	void topPAtZeroIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new LlamaCppOcrProperties(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL,
						CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, 4096, 0.1, 0.0, 1));
	}

	@Test
	void topPAboveOneIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new LlamaCppOcrProperties(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL,
						CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, 4096, 0.1, 1.1, 1));
	}

	@Test
	void maxConcurrentRequestsOtherThanOneIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new LlamaCppOcrProperties(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL,
						CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, 4096, 0.1, 0.9, 2));
		assertThrows(IllegalArgumentException.class,
				() -> new LlamaCppOcrProperties(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL,
						CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, 4096, 0.1, 0.9, 0));
	}

	@Test
	void toStringContainsNoPii() {
		LlamaCppOcrProperties props = newProps(CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, 4096, 0.1, 0.9, 1);
		String str = props.toString();
		assertTrue(str.contains("serverOrigin=" + PRODUCTION_ORIGIN));
		assertTrue(str.contains("model=" + PRODUCTION_MODEL));
		assertFalse(str.contains("password"));
		assertFalse(str.contains("secret"));
		assertFalse(str.contains("apiKey"));
	}

	@Test
	void noApiKeyOrEnabledPropertyExists() {
		LlamaCppOcrProperties props = newProps(CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, 4096, 0.1, 0.9, 1);
		String str = props.toString();
		assertFalse(str.contains("apiKey"));
		assertFalse(str.contains("enabled"));
	}

	@Test
	void loopbackOriginIsAccepted() {
		LlamaCppOcrProperties props = new LlamaCppOcrProperties(
				"http://127.0.0.1:12345", PRODUCTION_PATH, PRODUCTION_MODEL,
				CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, 4096, 0.1, 0.9, 1);
		assertEquals("127.0.0.1", props.serverOrigin().getHost());
		assertEquals(12345, props.serverOrigin().getPort());
	}

	private static LlamaCppOcrProperties newProps(Duration connect, Duration read, Duration call,
			long maxImageBytes, long maxResponseBytes, int maxOcrChars, int maxTokens, double temp, double topP,
			int maxConcurrent) {
		return new LlamaCppOcrProperties(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL,
				connect, read, call, maxImageBytes, maxResponseBytes, maxOcrChars, maxTokens, temp, topP,
				maxConcurrent);
	}

}
