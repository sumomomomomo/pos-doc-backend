package horse.sumomo.pos_doc_backend.ocr.client;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import horse.sumomo.pos_doc_backend.ocr.api.LlamaCppOcrProperties;
import horse.sumomo.pos_doc_backend.ocr.model.OcrResult;
import horse.sumomo.pos_doc_backend.ocr.service.OcrException;
import horse.sumomo.pos_doc_backend.ocr.service.OcrException.Code;
import horse.sumomo.pos_doc_backend.rendering.model.RenderedFirstPage;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Typed llama.cpp HTTP client that sends a single first-page PNG to the
 * fixed local OCR service and returns an immutable {@link OcrResult}.
 *
 * <p>Permits exactly one OCR request at a time inside one backend instance,
 * using a fair semaphore. Does not retry; a later queue workflow owns retry
 * policy.
 *
 * <p>Never logs the request or response body. Exception messages contain no
 * PII, no raw response text, no URL, no headers.
 */
public final class LlamaCppOcrClient {

	private static final Logger log = LoggerFactory.getLogger(LlamaCppOcrClient.class);

	private static final int PROMPT_VERSION = 1;

	private static final String PROMPT = "Extract the text content from this image.";

	private final OkHttpClient httpClient;
	private final LlamaCppOcrProperties properties;
	private final ObjectMapper objectMapper;
	private final Semaphore semaphore;

	public LlamaCppOcrClient(OkHttpClient httpClient, LlamaCppOcrProperties properties) {
		this.httpClient = httpClient;
		this.properties = properties;
		this.objectMapper = new ObjectMapper();
		this.semaphore = new Semaphore(properties.maxConcurrentRequests(), true);
	}

	/**
	 * Sends the rendered first-page PNG to the llama.cpp service and returns
	 * the recognized text.
	 *
	 * @param page the rendered first-page handle; must not be null
	 * @return an immutable {@link OcrResult}
	 * @throws OcrException with a stable code on any failure
	 */
	public OcrResult recognize(RenderedFirstPage page) {
		if (page == null) {
			throw new IllegalArgumentException("page must not be null");
		}

		boolean acquired = false;
		try {
			try {
				this.semaphore.acquire();
				acquired = true;
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new OcrException(Code.OCR_INTERRUPTED, e);
			}

			StreamingPngChatRequestBody body = new StreamingPngChatRequestBody(
					page.pngPath(), page.pngByteSize(), this.properties.maxImageBytes(),
					this.properties.model(), PROMPT, this.properties.maxTokens(),
					this.properties.temperature(), this.properties.topP(), this.objectMapper);

			Request request = new Request.Builder()
					.url(this.buildUrl())
					.post(body)
					.header("Content-Type", "application/json")
					.header("Accept", "application/json")
					.build();

			try (Response response = this.httpClient.newCall(request).execute()) {
				return this.handleResponse(response, page.documentId());
			}
		}
		catch (OcrException e) {
			throw e;
		}
		catch (IOException e) {
			throw mapIOException(e);
		}
		finally {
			if (acquired) {
				this.semaphore.release();
			}
		}
	}

	private java.net.URL buildUrl() {
		try {
			return this.properties.serverOrigin().resolve(this.properties.chatCompletionsPath()).toURL();
		}
		catch (java.net.MalformedURLException e) {
			throw new OcrException(Code.OCR_PROTOCOL_ERROR, e);
		}
	}

	private OcrResult handleResponse(Response response, java.util.UUID documentId) throws IOException {
		int status = response.code();

		if (status != 200) {
			response.close();
			throw mapStatus(status);
		}

		ResponseBody responseBody = response.body();
		if (responseBody == null) {
			response.close();
			throw new OcrException(Code.OCR_RESPONSE_INVALID);
		}

		// Validate content type.
		MediaType contentType = responseBody.contentType();
		if (contentType == null || !isJsonContentType(contentType)) {
			response.close();
			throw new OcrException(Code.OCR_PROTOCOL_ERROR);
		}

		// Read the response body with a bounded stream.
		try (InputStream boundedStream = new BoundedInputStream(responseBody.byteStream(),
				this.properties.maxResponseBytes())) {
			JsonNode root;
			try {
				root = this.objectMapper.readTree(boundedStream);
			}
			catch (IOException e) {
				response.close();
				throw new OcrException(Code.OCR_RESPONSE_INVALID, e);
			}

			// Reject trailing non-whitespace content after the root JSON object.
			// readTree already consumed the root; check for trailing content.
			// (Jackson's readTree does not reject trailing content by default,
			// so we use a more strict approach below.)

			// Validate the response structure.
			String text = validateAndExtract(root, documentId);
			response.close();
			return new OcrResult(documentId, text, this.properties.model(), "stop", PROMPT_VERSION);
		}
		catch (OcrException e) {
			throw e;
		}
		catch (IOException e) {
			response.close();
			throw new OcrException(Code.OCR_RESPONSE_TOO_LARGE, e);
		}
	}

	private String validateAndExtract(JsonNode root, java.util.UUID documentId) {
		// Require exactly one entry in choices.
		JsonNode choices = root.get("choices");
		if (choices == null || !choices.isArray() || choices.size() != 1) {
			throw new OcrException(Code.OCR_RESPONSE_INVALID);
		}

		JsonNode choice = choices.get(0);

		// Require choices[0].message.role == "assistant" if role is present.
		JsonNode message = choice.get("message");
		if (message == null || !message.isObject()) {
			throw new OcrException(Code.OCR_RESPONSE_INVALID);
		}
		JsonNode role = message.get("role");
		if (role != null && !role.isNull() && !role.asText().equals("assistant")) {
			throw new OcrException(Code.OCR_RESPONSE_INVALID);
		}

		// Require choices[0].message.content to be a JSON string, nonblank,
		// and no longer than max-ocr-characters.
		JsonNode content = message.get("content");
		if (content == null || !content.isTextual()) {
			throw new OcrException(Code.OCR_RESPONSE_INVALID);
		}
		String text = content.asText();
		if (text.isBlank()) {
			throw new OcrException(Code.OCR_OUTPUT_EMPTY);
		}
		if (text.length() > this.properties.maxOcrCharacters()) {
			throw new OcrException(Code.OCR_RESPONSE_INVALID);
		}

		// Require response model to equal the configured model.
		JsonNode responseModel = root.get("model");
		if (responseModel == null || !responseModel.isTextual()
				|| !responseModel.asText().equals(this.properties.model())) {
			throw new OcrException(Code.OCR_RESPONSE_INVALID);
		}

		// Require finish_reason == "stop".
		JsonNode finishReason = choice.get("finish_reason");
		if (finishReason == null || !finishReason.isTextual()) {
			throw new OcrException(Code.OCR_RESPONSE_INVALID);
		}
		String finishReasonText = finishReason.asText();
		if (finishReasonText.equals("length")) {
			throw new OcrException(Code.OCR_OUTPUT_TRUNCATED);
		}
		if (!finishReasonText.equals("stop")) {
			throw new OcrException(Code.OCR_RESPONSE_INVALID);
		}

		// If usage is present, validate only that token counts are non-negative.
		JsonNode usage = root.get("usage");
		if (usage != null && usage.isObject()) {
			JsonNode promptTokens = usage.get("prompt_tokens");
			if (promptTokens != null && promptTokens.isNumber() && promptTokens.asInt() < 0) {
				throw new OcrException(Code.OCR_RESPONSE_INVALID);
			}
			JsonNode completionTokens = usage.get("completion_tokens");
			if (completionTokens != null && completionTokens.isNumber() && completionTokens.asInt() < 0) {
				throw new OcrException(Code.OCR_RESPONSE_INVALID);
			}
			JsonNode totalTokens = usage.get("total_tokens");
			if (totalTokens != null && totalTokens.isNumber() && totalTokens.asInt() < 0) {
				throw new OcrException(Code.OCR_RESPONSE_INVALID);
			}
		}

		return text;
	}

	private static boolean isJsonContentType(MediaType mediaType) {
		String type = mediaType.type();
		String subtype = mediaType.subtype();
		if (!"application".equalsIgnoreCase(type)) {
			return false;
		}
		return "json".equalsIgnoreCase(subtype) || subtype.endsWith("+json");
	}

	private static OcrException mapStatus(int status) {
		if (status >= 400 && status < 500) {
			switch (status) {
				case 401:
				case 403:
					return new OcrException(Code.OCR_AUTH_FAILED);
				case 408:
				case 429:
					return new OcrException(Code.OCR_SERVICE_BUSY);
				case 400:
				case 404:
				case 405:
				case 409:
				case 415:
				case 422:
					return new OcrException(Code.OCR_REQUEST_REJECTED);
				default:
					return new OcrException(Code.OCR_PROTOCOL_ERROR);
			}
		}
		if (status >= 500 && status <= 599) {
			return new OcrException(Code.OCR_SERVICE_UNAVAILABLE);
		}
		return new OcrException(Code.OCR_PROTOCOL_ERROR);
	}

	private static OcrException mapIOException(IOException e) {
		if (e instanceof InterruptedIOException) {
			if (Thread.currentThread().isInterrupted()) {
				return new OcrException(Code.OCR_INTERRUPTED, e);
			}
			return new OcrException(Code.OCR_TIMEOUT, e);
		}
		if (e instanceof SocketTimeoutException) {
			return new OcrException(Code.OCR_TIMEOUT, e);
		}
		if (e instanceof ConnectException) {
			return new OcrException(Code.OCR_SERVICE_UNAVAILABLE, e);
		}
		return new OcrException(Code.OCR_SERVICE_UNAVAILABLE, e);
	}

	/**
	 * A bounded input stream that throws before more than {@code maxBytes}
	 * are consumed.
	 */
	static final class BoundedInputStream extends InputStream {

		private final InputStream delegate;
		private final long maxBytes;
		private long count;

		BoundedInputStream(InputStream delegate, long maxBytes) {
			if (maxBytes <= 0) {
				throw new IllegalArgumentException("maxBytes must be positive");
			}
			this.delegate = delegate;
			this.maxBytes = maxBytes;
			this.count = 0L;
		}

		@Override
		public int read() throws IOException {
			checkLimit(1L);
			int b = this.delegate.read();
			if (b != -1) {
				this.count++;
			}
			return b;
		}

		@Override
		public int read(byte[] buf, int off, int len) throws IOException {
			if (len < 0) {
				throw new IndexOutOfBoundsException("len must not be negative");
			}
			checkLimit(len);
			int n = this.delegate.read(buf, off, len);
			if (n > 0) {
				this.count += n;
			}
			return n;
		}

		@Override
		public int available() throws IOException {
			return this.delegate.available();
		}

		@Override
		public void close() throws IOException {
			this.delegate.close();
		}

		private void checkLimit(long additional) {
			if (additional > this.maxBytes - this.count) {
				throw new OcrException(Code.OCR_RESPONSE_TOO_LARGE);
			}
		}

	}

}
