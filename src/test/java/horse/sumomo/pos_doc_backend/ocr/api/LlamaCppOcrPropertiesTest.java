package horse.sumomo.pos_doc_backend.ocr.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import horse.sumomo.pos_doc_backend.rendering.api.FirstPageRenderingProperties;

/**
 * Unit tests for {@link LlamaCppOcrProperties} validation rules.
 *
 * <p>The "valid" baseline uses the Qwen 3.5 8B sampling contract from
 * application.yaml (temperature 0.7, top-p 0.8, top-k 20, min-p 0.0,
 * presence-penalty 1.5, repeat-penalty 1.0, max-tokens 128).
 */
class LlamaCppOcrPropertiesTest {

	private static final String PRODUCTION_ORIGIN = "http://192.168.1.34:8080";
	private static final String PRODUCTION_PATH = "/v1/chat/completions";
	private static final String PRODUCTION_MODEL = "task12-test-model";
	private static final Duration CONNECT = Duration.ofSeconds(5);
	private static final Duration READ = Duration.ofSeconds(300);
	private static final Duration CALL = Duration.ofSeconds(310);

	// Qwen 3.5 8B sampling contract.
	private static final int MAX_TOKENS = 128;
	private static final double TEMPERATURE = 0.7;
	private static final double TOP_P = 0.8;
	private static final int TOP_K = 20;
	private static final double MIN_P = 0.0;
	private static final double PRESENCE_PENALTY = 1.5;
	private static final double REPEAT_PENALTY = 1.0;

	@Test
	void documentedQwenContractBindsSuccessfully() {
		LlamaCppOcrProperties props = validProps();
		assertEquals(URI.create(PRODUCTION_ORIGIN), props.serverOrigin());
		assertEquals(PRODUCTION_PATH, props.chatCompletionsPath());
		assertEquals(PRODUCTION_MODEL, props.model());
		assertEquals(CONNECT, props.connectTimeout());
		assertEquals(READ, props.readTimeout());
		assertEquals(CALL, props.callTimeout());
		assertEquals(33554432L, props.maxImageBytes());
		assertEquals(2097152L, props.maxResponseBytes());
		assertEquals(1000000, props.maxOcrCharacters());
		assertEquals(MAX_TOKENS, props.maxTokens());
		assertEquals(TEMPERATURE, props.temperature(), 0.0001);
		assertEquals(TOP_P, props.topP(), 0.0001);
		assertEquals(TOP_K, props.topK());
		assertEquals(MIN_P, props.minP(), 0.0001);
		assertEquals(PRESENCE_PENALTY, props.presencePenalty(), 0.0001);
		assertEquals(REPEAT_PENALTY, props.repeatPenalty(), 0.0001);
		assertEquals(1, props.maxConcurrentRequests());
		assertEquals(3, props.maxAttempts());
		assertEquals(0L, props.retryBackoffMs());
	}

	@Test
	void productionServerOriginBindsExactly() {
		LlamaCppOcrProperties props = validProps();
		assertEquals("http", props.serverOrigin().getScheme());
		assertEquals("192.168.1.34", props.serverOrigin().getHost());
		assertEquals(8080, props.serverOrigin().getPort());
	}

	@Test
	void invalidSchemeIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> c("ftp://192.168.1.34:8080", PRODUCTION_PATH, PRODUCTION_MODEL,
						CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, MAX_TOKENS, TEMPERATURE, TOP_P,
						TOP_K, MIN_P, PRESENCE_PENALTY, REPEAT_PENALTY, 1, 3, 0));
	}

	@Test
	void userInfoIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> c("http://user:pass@192.168.1.34:8080", PRODUCTION_PATH, PRODUCTION_MODEL,
						CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, MAX_TOKENS, TEMPERATURE, TOP_P,
						TOP_K, MIN_P, PRESENCE_PENALTY, REPEAT_PENALTY, 1, 3, 0));
	}

	@Test
	void queryIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> c("http://192.168.1.34:8080?debug=true", PRODUCTION_PATH, PRODUCTION_MODEL,
						CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, MAX_TOKENS, TEMPERATURE, TOP_P,
						TOP_K, MIN_P, PRESENCE_PENALTY, REPEAT_PENALTY, 1, 3, 0));
	}

	@Test
	void fragmentIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> c("http://192.168.1.34:8080#frag", PRODUCTION_PATH, PRODUCTION_MODEL,
						CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, MAX_TOKENS, TEMPERATURE, TOP_P,
						TOP_K, MIN_P, PRESENCE_PENALTY, REPEAT_PENALTY, 1, 3, 0));
	}

	@Test
	void nonEmptyOriginPathIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> c("http://192.168.1.34:8080/some/path", PRODUCTION_PATH, PRODUCTION_MODEL,
						CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, MAX_TOKENS, TEMPERATURE, TOP_P,
						TOP_K, MIN_P, PRESENCE_PENALTY, REPEAT_PENALTY, 1, 3, 0));
	}

	@Test
	void blankModelIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> c(PRODUCTION_ORIGIN, PRODUCTION_PATH, "  ",
						CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, MAX_TOKENS, TEMPERATURE, TOP_P,
						TOP_K, MIN_P, PRESENCE_PENALTY, REPEAT_PENALTY, 1, 3, 0));
	}

	@Test
	void blankChatCompletionsPathIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> c(PRODUCTION_ORIGIN, "  ", PRODUCTION_MODEL,
						CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, MAX_TOKENS, TEMPERATURE, TOP_P,
						TOP_K, MIN_P, PRESENCE_PENALTY, REPEAT_PENALTY, 1, 3, 0));
	}

	@Test
	void differentChatCompletionsPathIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> c(PRODUCTION_ORIGIN, "/v1/completions", PRODUCTION_MODEL,
						CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, MAX_TOKENS, TEMPERATURE, TOP_P,
						TOP_K, MIN_P, PRESENCE_PENALTY, REPEAT_PENALTY, 1, 3, 0));
	}

	@Test
	void zeroConnectTimeoutIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> c(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL,
						Duration.ZERO, READ, CALL, 33554432L, 2097152L, 1000000, MAX_TOKENS, TEMPERATURE, TOP_P,
						TOP_K, MIN_P, PRESENCE_PENALTY, REPEAT_PENALTY, 1, 3, 0));
	}

	@Test
	void negativeReadTimeoutIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> c(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL,
						CONNECT, Duration.ofSeconds(-1), CALL, 33554432L, 2097152L, 1000000, MAX_TOKENS, TEMPERATURE, TOP_P,
						TOP_K, MIN_P, PRESENCE_PENALTY, REPEAT_PENALTY, 1, 3, 0));
	}

	@Test
	void zeroCallTimeoutIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> c(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL,
						CONNECT, READ, Duration.ZERO, 33554432L, 2097152L, 1000000, MAX_TOKENS, TEMPERATURE, TOP_P,
						TOP_K, MIN_P, PRESENCE_PENALTY, REPEAT_PENALTY, 1, 3, 0));
	}

	@Test
	void connectTimeoutGreaterThanCallTimeoutIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> c(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL,
						Duration.ofSeconds(310), READ, CALL, 33554432L, 2097152L, 1000000, MAX_TOKENS, TEMPERATURE, TOP_P,
						TOP_K, MIN_P, PRESENCE_PENALTY, REPEAT_PENALTY, 1, 3, 0));
	}

	@Test
	void connectTimeoutEqualToCallTimeoutIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> c(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL,
						CALL, READ, CALL, 33554432L, 2097152L, 1000000, MAX_TOKENS, TEMPERATURE, TOP_P,
						TOP_K, MIN_P, PRESENCE_PENALTY, REPEAT_PENALTY, 1, 3, 0));
	}

	@Test
	void readTimeoutGreaterThanCallTimeoutIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> c(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL,
						CONNECT, Duration.ofSeconds(311), CALL, 33554432L, 2097152L, 1000000, MAX_TOKENS, TEMPERATURE, TOP_P,
						TOP_K, MIN_P, PRESENCE_PENALTY, REPEAT_PENALTY, 1, 3, 0));
	}

	@Test
	void zeroMaxImageBytesIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> c(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL,
						CONNECT, READ, CALL, 0L, 2097152L, 1000000, MAX_TOKENS, TEMPERATURE, TOP_P,
						TOP_K, MIN_P, PRESENCE_PENALTY, REPEAT_PENALTY, 1, 3, 0));
	}

	@Test
	void zeroMaxResponseBytesIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> c(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL,
						CONNECT, READ, CALL, 33554432L, 0L, 1000000, MAX_TOKENS, TEMPERATURE, TOP_P,
						TOP_K, MIN_P, PRESENCE_PENALTY, REPEAT_PENALTY, 1, 3, 0));
	}

	@Test
	void zeroMaxOcrCharactersIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> c(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL,
						CONNECT, READ, CALL, 33554432L, 2097152L, 0, MAX_TOKENS, TEMPERATURE, TOP_P,
						TOP_K, MIN_P, PRESENCE_PENALTY, REPEAT_PENALTY, 1, 3, 0));
	}

	@Test
	void maxImageBytesAboveRenderingPngLimitIsRejected() {
		LlamaCppOcrProperties props = c(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL,
				CONNECT, READ, CALL, 33554433L, 2097152L, 1000000, MAX_TOKENS, TEMPERATURE, TOP_P,
				TOP_K, MIN_P, PRESENCE_PENALTY, REPEAT_PENALTY, 1, 3, 0);
		FirstPageRenderingProperties rendering = new FirstPageRenderingProperties(200, 52428800L, 5000, 5000,
				16000000L, 33554432L, 1);
		assertThrows(IllegalArgumentException.class,
				() -> props.validateAgainstRenderingLimit(rendering.maxPngBytes()));
	}

	@Test
	void maxImageBytesEqualToRenderingPngLimitIsAccepted() {
		LlamaCppOcrProperties props = validProps();
		FirstPageRenderingProperties rendering = new FirstPageRenderingProperties(200, 52428800L, 5000, 5000,
				16000000L, 33554432L, 1);
		props.validateAgainstRenderingLimit(rendering.maxPngBytes());
	}

	@Test
	void maxTokensBelowOneIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> c(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL,
						CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, 0, TEMPERATURE, TOP_P,
						TOP_K, MIN_P, PRESENCE_PENALTY, REPEAT_PENALTY, 1, 3, 0));
	}

	@Test
	void maxTokensAbove8192IsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> c(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL,
						CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, 8193, TEMPERATURE, TOP_P,
						TOP_K, MIN_P, PRESENCE_PENALTY, REPEAT_PENALTY, 1, 3, 0));
	}

	@Test
	void temperatureBelowZeroIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> c(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL,
						CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, MAX_TOKENS, -0.1, TOP_P,
						TOP_K, MIN_P, PRESENCE_PENALTY, REPEAT_PENALTY, 1, 3, 0));
	}

	@Test
	void temperatureAboveTwoIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> c(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL,
						CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, MAX_TOKENS, 2.1, TOP_P,
						TOP_K, MIN_P, PRESENCE_PENALTY, REPEAT_PENALTY, 1, 3, 0));
	}

	@Test
	void topPAtZeroIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> c(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL,
						CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, MAX_TOKENS, TEMPERATURE, 0.0,
						TOP_K, MIN_P, PRESENCE_PENALTY, REPEAT_PENALTY, 1, 3, 0));
	}

	@Test
	void topPAboveOneIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> c(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL,
						CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, MAX_TOKENS, TEMPERATURE, 1.1,
						TOP_K, MIN_P, PRESENCE_PENALTY, REPEAT_PENALTY, 1, 3, 0));
	}

	@Test
	void topKNegativeIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> c(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL,
						CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, MAX_TOKENS, TEMPERATURE, TOP_P,
						-1, MIN_P, PRESENCE_PENALTY, REPEAT_PENALTY, 1, 3, 0));
	}

	@Test
	void topKZeroIsAccepted() {
		LlamaCppOcrProperties props = c(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL,
				CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, MAX_TOKENS, TEMPERATURE, TOP_P,
				0, MIN_P, PRESENCE_PENALTY, REPEAT_PENALTY, 1, 3, 0);
		assertEquals(0, props.topK());
	}

	@Test
	void minPBelowZeroIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> c(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL,
						CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, MAX_TOKENS, TEMPERATURE, TOP_P,
						TOP_K, -0.1, PRESENCE_PENALTY, REPEAT_PENALTY, 1, 3, 0));
	}

	@Test
	void minPAboveOneIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> c(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL,
						CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, MAX_TOKENS, TEMPERATURE, TOP_P,
						TOP_K, 1.1, PRESENCE_PENALTY, REPEAT_PENALTY, 1, 3, 0));
	}

	@Test
	void minPOutOfRangeBoundariesAreAccepted() {
		assertEquals(0.0, c(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL,
				CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, MAX_TOKENS, TEMPERATURE, TOP_P,
				TOP_K, 0.0, PRESENCE_PENALTY, REPEAT_PENALTY, 1, 3, 0).minP(), 0.0001);
		assertEquals(1.0, c(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL,
				CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, MAX_TOKENS, TEMPERATURE, TOP_P,
				TOP_K, 1.0, PRESENCE_PENALTY, REPEAT_PENALTY, 1, 3, 0).minP(), 0.0001);
	}

	@Test
	void presencePenaltyBelowMinusTwoIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> c(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL,
						CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, MAX_TOKENS, TEMPERATURE, TOP_P,
						TOP_K, MIN_P, -2.1, REPEAT_PENALTY, 1, 3, 0));
	}

	@Test
	void presencePenaltyAboveTwoIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> c(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL,
						CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, MAX_TOKENS, TEMPERATURE, TOP_P,
						TOP_K, MIN_P, 2.1, REPEAT_PENALTY, 1, 3, 0));
	}

	@Test
	void presencePenaltyBoundariesAreAccepted() {
		assertEquals(-2.0, c(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL,
				CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, MAX_TOKENS, TEMPERATURE, TOP_P,
				TOP_K, MIN_P, -2.0, REPEAT_PENALTY, 1, 3, 0).presencePenalty(), 0.0001);
		assertEquals(2.0, c(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL,
				CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, MAX_TOKENS, TEMPERATURE, TOP_P,
				TOP_K, MIN_P, 2.0, REPEAT_PENALTY, 1, 3, 0).presencePenalty(), 0.0001);
	}

	@Test
	void repeatPenaltyZeroIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> c(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL,
						CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, MAX_TOKENS, TEMPERATURE, TOP_P,
						TOP_K, MIN_P, PRESENCE_PENALTY, 0.0, 1, 3, 0));
	}

	@Test
	void repeatPenaltyNegativeIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> c(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL,
						CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, MAX_TOKENS, TEMPERATURE, TOP_P,
						TOP_K, MIN_P, PRESENCE_PENALTY, -1.0, 1, 3, 0));
	}

	@ParameterizedTest(name = "{0} rejects non-finite value {1}")
	@MethodSource("nonFiniteSamplingValues")
	void nonFiniteSamplingValuesAreRejected(String property, double badValue) {
		assertThrows(IllegalArgumentException.class, () -> buildWithNonFinite(property, badValue));
	}

	static Stream<Arguments> nonFiniteSamplingValues() {
		Double[] values = { Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY };
		return Stream.of("temperature", "topP", "minP", "presencePenalty", "repeatPenalty")
				.flatMap(property -> Arrays.stream(values)
						.map(bad -> Arguments.of(property, bad)));
	}

	private static LlamaCppOcrProperties buildWithNonFinite(String property, double bad) {
		return switch (property) {
			case "temperature" -> c(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL, CONNECT, READ, CALL,
					33554432L, 2097152L, 1000000, MAX_TOKENS, bad, TOP_P, TOP_K, MIN_P, PRESENCE_PENALTY, REPEAT_PENALTY, 1, 3, 0);
			case "topP" -> c(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL, CONNECT, READ, CALL,
					33554432L, 2097152L, 1000000, MAX_TOKENS, TEMPERATURE, bad, TOP_K, MIN_P, PRESENCE_PENALTY, REPEAT_PENALTY, 1, 3, 0);
			case "minP" -> c(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL, CONNECT, READ, CALL,
					33554432L, 2097152L, 1000000, MAX_TOKENS, TEMPERATURE, TOP_P, TOP_K, bad, PRESENCE_PENALTY, REPEAT_PENALTY, 1, 3, 0);
			case "presencePenalty" -> c(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL, CONNECT, READ, CALL,
					33554432L, 2097152L, 1000000, MAX_TOKENS, TEMPERATURE, TOP_P, TOP_K, MIN_P, bad, REPEAT_PENALTY, 1, 3, 0);
			case "repeatPenalty" -> c(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL, CONNECT, READ, CALL,
					33554432L, 2097152L, 1000000, MAX_TOKENS, TEMPERATURE, TOP_P, TOP_K, MIN_P, PRESENCE_PENALTY, bad, 1, 3, 0);
			default -> throw new IllegalArgumentException("unknown property: " + property);
		};
	}

	@Test
	void maxConcurrentRequestsOtherThanOneIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> c(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL,
						CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, MAX_TOKENS, TEMPERATURE, TOP_P,
						TOP_K, MIN_P, PRESENCE_PENALTY, REPEAT_PENALTY, 2, 3, 0));
		assertThrows(IllegalArgumentException.class,
				() -> c(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL,
						CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, MAX_TOKENS, TEMPERATURE, TOP_P,
						TOP_K, MIN_P, PRESENCE_PENALTY, REPEAT_PENALTY, 0, 3, 0));
	}

	@Test
	void maxAttemptsBelowOneIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> c(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL,
						CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, MAX_TOKENS, TEMPERATURE, TOP_P,
						TOP_K, MIN_P, PRESENCE_PENALTY, REPEAT_PENALTY, 1, 0, 0));
	}

	@Test
	void maxAttemptsAboveThreeIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> c(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL,
						CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, MAX_TOKENS, TEMPERATURE, TOP_P,
						TOP_K, MIN_P, PRESENCE_PENALTY, REPEAT_PENALTY, 1, 4, 0));
	}

	@Test
	void negativeRetryBackoffIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> c(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL,
						CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, MAX_TOKENS, TEMPERATURE, TOP_P,
						TOP_K, MIN_P, PRESENCE_PENALTY, REPEAT_PENALTY, 1, 3, -1));
	}

	@Test
	void loopbackOriginIsAccepted() {
		LlamaCppOcrProperties props = c(
				"http://127.0.0.1:12345", PRODUCTION_PATH, PRODUCTION_MODEL,
				CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, MAX_TOKENS, TEMPERATURE, TOP_P,
				TOP_K, MIN_P, PRESENCE_PENALTY, REPEAT_PENALTY, 1, 3, 0);
		assertEquals("127.0.0.1", props.serverOrigin().getHost());
		assertEquals(12345, props.serverOrigin().getPort());
	}

	@Test
	void toStringContainsNoPii() {
		String str = validProps().toString();
		assertTrue(str.contains("serverOrigin=" + PRODUCTION_ORIGIN));
		assertTrue(str.contains("model=" + PRODUCTION_MODEL));
		assertTrue(str.contains("topK=" + TOP_K));
		assertTrue(str.contains("presencePenalty=" + PRESENCE_PENALTY));
		assertFalse(str.contains("password"));
		assertFalse(str.contains("secret"));
		assertFalse(str.contains("apiKey"));
	}

	@Test
	void noApiKeyOrEnabledPropertyExists() {
		String str = validProps().toString();
		assertFalse(str.contains("apiKey"));
		assertFalse(str.contains("enabled"));
	}

	private static LlamaCppOcrProperties validProps() {
		return c(PRODUCTION_ORIGIN, PRODUCTION_PATH, PRODUCTION_MODEL,
				CONNECT, READ, CALL, 33554432L, 2097152L, 1000000, MAX_TOKENS, TEMPERATURE, TOP_P,
				TOP_K, MIN_P, PRESENCE_PENALTY, REPEAT_PENALTY, 1, 3, 0);
	}

	/** Thin wrapper so the many 19-argument constructor calls read on fewer lines. */
	private static LlamaCppOcrProperties c(String origin, String path, String model, Duration connect,
			Duration read, Duration call, long maxImageBytes, long maxResponseBytes, int maxOcrCharacters,
			int maxTokens, double temperature, double topP, int topK, double minP, double presencePenalty,
			double repeatPenalty, int maxConcurrentRequests, int maxAttempts, long retryBackoffMs) {
		return new LlamaCppOcrProperties(origin, path, model, connect, read, call, maxImageBytes,
				maxResponseBytes, maxOcrCharacters, maxTokens, temperature, topP, topK, minP, presencePenalty,
				repeatPenalty, maxConcurrentRequests, maxAttempts, retryBackoffMs);
	}

}
