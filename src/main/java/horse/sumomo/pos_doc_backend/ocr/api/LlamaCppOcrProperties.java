package horse.sumomo.pos_doc_backend.ocr.api;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed, validated configuration for the local llama.cpp OCR service.
 *
 * <p>Bound from {@code app.ocr.llama-cpp.*}. The chat completions path is fixed
 * for this deployment and must not be derived from request data, document
 * metadata, or remote responses. The server origin and the model ID are
 * deployment-specific: the production server origin defaults to the LAN llama.cpp
 * endpoint, and the model ID is supplied by the deployer (whatever llama.cpp
 * reports as {@code data[0].id} from {@code /models}) via the {@code OCR_MODEL}
 * environment variable. The backend never discovers its model by calling
 * {@code /models}.
 *
 * <p>{@link #toString()} exposes only safe configuration values; there is no
 * API-key property.
 */
@ConfigurationProperties(prefix = "app.ocr.llama-cpp")
public final class LlamaCppOcrProperties {

	static final String REQUIRED_CHAT_COMPLETIONS_PATH = "/v1/chat/completions";
	static final int MIN_MAX_TOKENS = 1;
	static final int MAX_MAX_TOKENS = 8192;
	static final double MIN_TEMPERATURE = 0.0;
	static final double MAX_TEMPERATURE = 2.0;
	static final double MIN_TOP_P = 0.0;
	static final double MAX_TOP_P = 1.0;
	static final double MIN_MIN_P = 0.0;
	static final double MAX_MIN_P = 1.0;
	static final double MIN_PRESENCE_PENALTY = -2.0;
	static final double MAX_PRESENCE_PENALTY = 2.0;
	static final double MIN_REPEAT_PENALTY = 0.0;
	static final int MIN_MAX_ATTEMPTS = 1;
	static final int MAX_MAX_ATTEMPTS = 3;

	private final URI serverOrigin;
	private final String chatCompletionsPath;
	private final String model;
	private final Duration connectTimeout;
	private final Duration readTimeout;
	private final Duration callTimeout;
	private final long maxImageBytes;
	private final long maxResponseBytes;
	private final int maxOcrCharacters;
	private final int maxTokens;
	private final double temperature;
	private final double topP;
	private final int topK;
	private final double minP;
	private final double presencePenalty;
	private final double repeatPenalty;
	private final int maxConcurrentRequests;
	private final int maxAttempts;
	private final long retryBackoffMs;

	public LlamaCppOcrProperties(String serverOrigin, String chatCompletionsPath, String model,
			Duration connectTimeout, Duration readTimeout, Duration callTimeout, long maxImageBytes,
			long maxResponseBytes, int maxOcrCharacters, int maxTokens, double temperature, double topP,
			int topK, double minP, double presencePenalty, double repeatPenalty,
			int maxConcurrentRequests, int maxAttempts, long retryBackoffMs) {
		if (isBlank(serverOrigin)) {
			throw new IllegalArgumentException("app.ocr.llama-cpp.server-origin must not be blank");
		}
		URI origin;
		try {
			origin = URI.create(serverOrigin.trim());
		}
		catch (IllegalArgumentException e) {
			throw new IllegalArgumentException(
					"app.ocr.llama-cpp.server-origin must be a valid URI: " + serverOrigin, e);
		}
		String scheme = origin.getScheme();
		if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
			throw new IllegalArgumentException(
					"app.ocr.llama-cpp.server-origin must use the http or https scheme: " + serverOrigin);
		}
		if (!origin.isAbsolute()) {
			throw new IllegalArgumentException(
					"app.ocr.llama-cpp.server-origin must be an absolute URI: " + serverOrigin);
		}
		if (origin.getUserInfo() != null) {
			throw new IllegalArgumentException(
					"app.ocr.llama-cpp.server-origin must not contain user-info: " + serverOrigin);
		}
		if (origin.getQuery() != null) {
			throw new IllegalArgumentException(
					"app.ocr.llama-cpp.server-origin must not contain a query string: " + serverOrigin);
		}
		if (origin.getFragment() != null) {
			throw new IllegalArgumentException(
					"app.ocr.llama-cpp.server-origin must not contain a fragment: " + serverOrigin);
		}
		if (origin.getRawPath() != null && !origin.getRawPath().isEmpty()) {
			throw new IllegalArgumentException(
					"app.ocr.llama-cpp.server-origin must not contain a path: " + serverOrigin);
		}
		if (isBlank(chatCompletionsPath)) {
			throw new IllegalArgumentException("app.ocr.llama-cpp.chat-completions-path must not be blank");
		}
		if (!REQUIRED_CHAT_COMPLETIONS_PATH.equals(chatCompletionsPath.trim())) {
			throw new IllegalArgumentException(
					"app.ocr.llama-cpp.chat-completions-path must be exactly " + REQUIRED_CHAT_COMPLETIONS_PATH);
		}
		if (isBlank(model)) {
			throw new IllegalArgumentException("app.ocr.llama-cpp.model must not be blank");
		}
		if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
			throw new IllegalArgumentException("app.ocr.llama-cpp.connect-timeout must be positive");
		}
		if (readTimeout == null || readTimeout.isZero() || readTimeout.isNegative()) {
			throw new IllegalArgumentException("app.ocr.llama-cpp.read-timeout must be positive");
		}
		if (callTimeout == null || callTimeout.isZero() || callTimeout.isNegative()) {
			throw new IllegalArgumentException("app.ocr.llama-cpp.call-timeout must be positive");
		}
		if (connectTimeout.compareTo(callTimeout) >= 0) {
			throw new IllegalArgumentException(
					"app.ocr.llama-cpp.connect-timeout must be < app.ocr.llama-cpp.call-timeout");
		}
		if (readTimeout.compareTo(callTimeout) > 0) {
			throw new IllegalArgumentException(
					"app.ocr.llama-cpp.read-timeout must be <= app.ocr.llama-cpp.call-timeout");
		}
		if (maxImageBytes <= 0) {
			throw new IllegalArgumentException("app.ocr.llama-cpp.max-image-bytes must be positive");
		}
		if (maxResponseBytes <= 0) {
			throw new IllegalArgumentException("app.ocr.llama-cpp.max-response-bytes must be positive");
		}
		if (maxOcrCharacters <= 0) {
			throw new IllegalArgumentException("app.ocr.llama-cpp.max-ocr-characters must be positive");
		}
		if (maxTokens < MIN_MAX_TOKENS || maxTokens > MAX_MAX_TOKENS) {
			throw new IllegalArgumentException(
					"app.ocr.llama-cpp.max-tokens must be within [" + MIN_MAX_TOKENS + ", " + MAX_MAX_TOKENS + "]");
		}
		if (!isFinite(temperature)) {
			throw new IllegalArgumentException("app.ocr.llama-cpp.temperature must be finite");
		}
		if (temperature < MIN_TEMPERATURE || temperature > MAX_TEMPERATURE) {
			throw new IllegalArgumentException(
					"app.ocr.llama-cpp.temperature must be within [" + MIN_TEMPERATURE + ", " + MAX_TEMPERATURE + "]");
		}
		if (!isFinite(topP)) {
			throw new IllegalArgumentException("app.ocr.llama-cpp.top-p must be finite");
		}
		if (topP <= MIN_TOP_P || topP > MAX_TOP_P) {
			throw new IllegalArgumentException(
					"app.ocr.llama-cpp.top-p must be within (" + MIN_TOP_P + ", " + MAX_TOP_P + "]");
		}
		if (topK < 0) {
			throw new IllegalArgumentException("app.ocr.llama-cpp.top-k must be non-negative");
		}
		if (!isFinite(minP)) {
			throw new IllegalArgumentException("app.ocr.llama-cpp.min-p must be finite");
		}
		if (minP < MIN_MIN_P || minP > MAX_MIN_P) {
			throw new IllegalArgumentException(
					"app.ocr.llama-cpp.min-p must be within [" + MIN_MIN_P + ", " + MAX_MIN_P + "]");
		}
		if (!isFinite(presencePenalty)) {
			throw new IllegalArgumentException("app.ocr.llama-cpp.presence-penalty must be finite");
		}
		if (presencePenalty < MIN_PRESENCE_PENALTY || presencePenalty > MAX_PRESENCE_PENALTY) {
			throw new IllegalArgumentException(
					"app.ocr.llama-cpp.presence-penalty must be within [" + MIN_PRESENCE_PENALTY + ", "
						+ MAX_PRESENCE_PENALTY + "]");
		}
		if (!isFinite(repeatPenalty)) {
			throw new IllegalArgumentException("app.ocr.llama-cpp.repeat-penalty must be finite");
		}
		if (repeatPenalty <= MIN_REPEAT_PENALTY) {
			throw new IllegalArgumentException("app.ocr.llama-cpp.repeat-penalty must be > 0");
		}
		if (maxConcurrentRequests != 1) {
			throw new IllegalArgumentException(
					"app.ocr.llama-cpp.max-concurrent-requests must be exactly 1");
		}
		if (maxAttempts < MIN_MAX_ATTEMPTS || maxAttempts > MAX_MAX_ATTEMPTS) {
			throw new IllegalArgumentException(
					"app.ocr.llama-cpp.max-attempts must be within [" + MIN_MAX_ATTEMPTS + ", " + MAX_MAX_ATTEMPTS
						+ "]");
		}
		if (retryBackoffMs < 0) {
			throw new IllegalArgumentException("app.ocr.llama-cpp.retry-backoff-ms must be non-negative");
		}
		this.serverOrigin = origin;
		this.chatCompletionsPath = chatCompletionsPath.trim();
		this.model = model.trim();
		this.connectTimeout = connectTimeout;
		this.readTimeout = readTimeout;
		this.callTimeout = callTimeout;
		this.maxImageBytes = maxImageBytes;
		this.maxResponseBytes = maxResponseBytes;
		this.maxOcrCharacters = maxOcrCharacters;
		this.maxTokens = maxTokens;
		this.temperature = temperature;
		this.topP = topP;
		this.topK = topK;
		this.minP = minP;
		this.presencePenalty = presencePenalty;
		this.repeatPenalty = repeatPenalty;
		this.maxConcurrentRequests = maxConcurrentRequests;
		this.maxAttempts = maxAttempts;
		this.retryBackoffMs = retryBackoffMs;
	}

	/**
	 * Validates the cross-property rule that the OCR image limit must not
	 * exceed the rendering PNG limit. Invoked at startup by the OCR
	 * configuration.
	 */
	public void validateAgainstRenderingLimit(long renderingMaxPngBytes) {
		if (renderingMaxPngBytes <= 0) {
			throw new IllegalArgumentException("rendering max-png-bytes must be positive");
		}
		if (this.maxImageBytes > renderingMaxPngBytes) {
			throw new IllegalArgumentException(
					"app.ocr.llama-cpp.max-image-bytes must be <= app.rendering.first-page.max-png-bytes");
		}
	}

	public URI serverOrigin() {
		return this.serverOrigin;
	}

	public String chatCompletionsPath() {
		return this.chatCompletionsPath;
	}

	public String model() {
		return this.model;
	}

	public Duration connectTimeout() {
		return this.connectTimeout;
	}

	public Duration readTimeout() {
		return this.readTimeout;
	}

	public Duration callTimeout() {
		return this.callTimeout;
	}

	public long maxImageBytes() {
		return this.maxImageBytes;
	}

	public long maxResponseBytes() {
		return this.maxResponseBytes;
	}

	public int maxOcrCharacters() {
		return this.maxOcrCharacters;
	}

	public int maxTokens() {
		return this.maxTokens;
	}

	public double temperature() {
		return this.temperature;
	}

	public double topP() {
		return this.topP;
	}

	public int topK() {
		return this.topK;
	}

	public double minP() {
		return this.minP;
	}

	public double presencePenalty() {
		return this.presencePenalty;
	}

	public double repeatPenalty() {
		return this.repeatPenalty;
	}

	public int maxConcurrentRequests() {
		return this.maxConcurrentRequests;
	}

	public int maxAttempts() {
		return this.maxAttempts;
	}

	public long retryBackoffMs() {
		return this.retryBackoffMs;
	}

	private static boolean isFinite(double value) {
		return !Double.isNaN(value) && !Double.isInfinite(value);
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	@Override
	public String toString() {
		return "LlamaCppOcrProperties [serverOrigin=" + this.serverOrigin
				+ ", chatCompletionsPath=" + this.chatCompletionsPath
				+ ", model=" + this.model
				+ ", connectTimeout=" + this.connectTimeout
				+ ", readTimeout=" + this.readTimeout
				+ ", callTimeout=" + this.callTimeout
				+ ", maxImageBytes=" + this.maxImageBytes
				+ ", maxResponseBytes=" + this.maxResponseBytes
				+ ", maxOcrCharacters=" + this.maxOcrCharacters
				+ ", maxTokens=" + this.maxTokens
				+ ", temperature=" + this.temperature
				+ ", topP=" + this.topP
				+ ", topK=" + this.topK
				+ ", minP=" + this.minP
				+ ", presencePenalty=" + this.presencePenalty
				+ ", repeatPenalty=" + this.repeatPenalty
				+ ", maxConcurrentRequests=" + this.maxConcurrentRequests
			+ ", maxAttempts=" + this.maxAttempts + ", retryBackoffMs=" + this.retryBackoffMs + "]";
	}

}
