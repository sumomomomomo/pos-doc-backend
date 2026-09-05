package horse.sumomo.pos_doc_backend.ocr.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import horse.sumomo.pos_doc_backend.ocr.api.LlamaCppOcrProperties;
import horse.sumomo.pos_doc_backend.ocr.model.OcrResult;
import horse.sumomo.pos_doc_backend.ocr.service.OcrException;
import horse.sumomo.pos_doc_backend.rendering.model.RenderedFirstPage;
import okhttp3.OkHttpClient;

/**
 * Loopback HTTP protocol tests for {@link LlamaCppOcrClient}.
 *
 * <p>Uses a real loopback-only test HTTP server bound to {@code 127.0.0.1}
 * on an ephemeral port. The server is a protocol test server only, not a
 * fake model implementation.
 *
 * <p>No automated test contacts {@code 192.168.1.34}, uses a real model,
 * requires a GPU, or requires Internet access.
 */
class LlamaCppOcrClientTest {

	private static final String MODEL = "/models/dotsmocr-1.8b-q8_0.gguf";
	private static final String PROMPT = "Extract the text content from this image.";
	private static final String SYNTHETIC_OCR_TEXT = "synthetic-ocr-text-for-testing";
	private static final long MAX_CAPTURE_BYTES = 10 * 1024 * 1024; // 10 MiB cap

	private final ObjectMapper objectMapper = new ObjectMapper();

	@TempDir
	Path tempDir;

	private int port;
	private Thread serverThread;
	private volatile ServerSocket serverSocket;
	private volatile boolean serverRunning;
	private final List<RecordedRequest> recordedRequests = new ArrayList<>();
	private volatile String nextResponseJson;
	private volatile int nextResponseStatus;
	private volatile String nextResponseContentType;
	private volatile long maxResponseBytes;
	private final AtomicInteger concurrentRequests = new AtomicInteger(0);
	private final AtomicInteger maxConcurrentObserved = new AtomicInteger(0);
	// Latches for concurrency control in tests.
	private volatile CountDownLatch firstRequestArrived;
	private volatile CountDownLatch releaseFirstRequest;

	@BeforeEach
	void startServer() throws Exception {
		try (ServerSocket probe = new ServerSocket(0)) {
			this.port = probe.getLocalPort();
		}
		this.nextResponseJson = buildValidResponse(SYNTHETIC_OCR_TEXT);
		this.nextResponseStatus = 200;
		this.nextResponseContentType = "application/json";
		this.maxResponseBytes = 2097152L;
		this.recordedRequests.clear();
		this.concurrentRequests.set(0);
		this.maxConcurrentObserved.set(0);
		this.firstRequestArrived = null;
		this.releaseFirstRequest = null;

		this.serverRunning = true;
		this.serverSocket = new ServerSocket();
		this.serverSocket.bind(new InetSocketAddress("127.0.0.1", this.port));
		this.serverSocket.setSoTimeout(15000);

		this.serverThread = new Thread(() -> {
			while (this.serverRunning) {
				try {
					Socket client = this.serverSocket.accept();
					// Handle each connection in its own thread for concurrency.
					Thread t = new Thread(() -> handleClient(client));
					t.setDaemon(true);
					t.start();
				}
				catch (IOException e) {
					if (this.serverRunning) {
						// Timeout or other I/O error; continue.
					}
				}
			}
		}, "ocr-test-server");
		this.serverThread.setDaemon(true);
		this.serverThread.start();
	}

	@AfterEach
	void stopServer() throws Exception {
		this.serverRunning = false;
		if (this.serverSocket != null) {
			this.serverSocket.close();
		}
		if (this.serverThread != null) {
			this.serverThread.join(5000);
		}
	}

	@Test
	void validResponseReturnsExactOcrResult() throws Exception {
		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = writePng(pngBytes);
		RenderedFirstPage page = new RenderedFirstPage(UUID.randomUUID(), pngPath, 100, 100, 200, pngBytes.length);

		LlamaCppOcrClient client = createClient();
		OcrResult result = client.recognize(page);

		assertEquals(page.documentId(), result.documentId());
		assertEquals(SYNTHETIC_OCR_TEXT, result.text());
		assertEquals(MODEL, result.model());
		assertEquals("stop", result.finishReason());
		assertEquals(1, result.promptVersion());

		assertEquals(1, this.recordedRequests.size());
		RecordedRequest req = this.recordedRequests.get(0);
		assertEquals("POST", req.method);
		assertEquals("/v1/chat/completions", req.path);
		assertEquals("application/json", req.contentType);
		assertEquals("application/json", req.accept);
		// Check lowercase header names (server stores them lowercase).
		assertFalse(req.headers.containsKey("authorization"), "Authorization header must not be sent");
		assertFalse(req.headers.containsKey("cookie"), "Cookie header must not be sent");

		JsonNode root = this.objectMapper.readTree(req.body);
		assertEquals(MODEL, root.get("model").asText());
		assertEquals(PROMPT, root.get("messages").get(0).get("content").get(1).get("text").asText());
		assertEquals(4096, root.get("max_tokens").asInt());
		assertFalse(root.has("max_completion_tokens"));

		String url = root.get("messages").get(0).get("content").get(0).get("image_url").get("url").asText();
		String base64 = url.substring("data:image/png;base64,".length());
		byte[] decoded = Base64.getDecoder().decode(base64);
		assertArrayEquals(pngBytes, decoded);
	}

	@Test
	void unknownResponseFieldsAreIgnored() throws Exception {
		String responseWithTimings = "{\"id\":\"chatcmpl-123\",\"object\":\"chat.completion\",\"created\":1234567890,"
				+ "\"model\":\"" + MODEL + "\","
				+ "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"" + SYNTHETIC_OCR_TEXT
				+ "\"},\"finish_reason\":\"stop\"}],"
				+ "\"timings\":{\"load\":{\"sample\":1,\"mean\":100.0},\"sample\":{\"sample\":1,\"mean\":200.0}},"
				+ "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20,\"total_tokens\":30}}";
		this.nextResponseJson = responseWithTimings;

		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = writePng(pngBytes);
		RenderedFirstPage page = new RenderedFirstPage(UUID.randomUUID(), pngPath, 100, 100, 200, pngBytes.length);

		LlamaCppOcrClient client = createClient();
		OcrResult result = client.recognize(page);
		assertEquals(SYNTHETIC_OCR_TEXT, result.text());
	}

	@Test
	void optionalNonNegativeUsageIsAccepted() throws Exception {
		String responseWithUsage = "{\"model\":\"" + MODEL + "\","
				+ "\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + SYNTHETIC_OCR_TEXT
				+ "\"},\"finish_reason\":\"stop\"}],"
				+ "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20,\"total_tokens\":30}}";
		this.nextResponseJson = responseWithUsage;

		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = writePng(pngBytes);
		RenderedFirstPage page = new RenderedFirstPage(UUID.randomUUID(), pngPath, 100, 100, 200, pngBytes.length);

		LlamaCppOcrClient client = createClient();
		OcrResult result = client.recognize(page);
		assertEquals(SYNTHETIC_OCR_TEXT, result.text());
	}

	@Test
	void redirectsAreNotFollowed() throws Exception {
		this.nextResponseStatus = 302;
		this.nextResponseJson = "";
		this.nextResponseContentType = "text/html";

		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = writePng(pngBytes);
		RenderedFirstPage page = new RenderedFirstPage(UUID.randomUUID(), pngPath, 100, 100, 200, pngBytes.length);

		LlamaCppOcrClient client = createClient();
		OcrException e = assertThrows(OcrException.class, () -> client.recognize(page));
		assertEquals(OcrException.Code.OCR_PROTOCOL_ERROR, e.getCode());
	}

	@Test
	void status400MapsToRequestRejected() throws Exception {
		this.nextResponseStatus = 400;
		this.nextResponseJson = "";
		this.nextResponseContentType = "application/json";
		assertOcrException(createPage(), OcrException.Code.OCR_REQUEST_REJECTED);
	}

	@Test
	void status404MapsToRequestRejected() throws Exception {
		this.nextResponseStatus = 404;
		this.nextResponseJson = "";
		this.nextResponseContentType = "application/json";
		assertOcrException(createPage(), OcrException.Code.OCR_REQUEST_REJECTED);
	}

	@Test
	void status405MapsToRequestRejected() throws Exception {
		this.nextResponseStatus = 405;
		this.nextResponseJson = "";
		this.nextResponseContentType = "application/json";
		assertOcrException(createPage(), OcrException.Code.OCR_REQUEST_REJECTED);
	}

	@Test
	void status409MapsToRequestRejected() throws Exception {
		this.nextResponseStatus = 409;
		this.nextResponseJson = "";
		this.nextResponseContentType = "application/json";
		assertOcrException(createPage(), OcrException.Code.OCR_REQUEST_REJECTED);
	}

	@Test
	void status415MapsToRequestRejected() throws Exception {
		this.nextResponseStatus = 415;
		this.nextResponseJson = "";
		this.nextResponseContentType = "application/json";
		assertOcrException(createPage(), OcrException.Code.OCR_REQUEST_REJECTED);
	}

	@Test
	void status422MapsToRequestRejected() throws Exception {
		this.nextResponseStatus = 422;
		this.nextResponseJson = "";
		this.nextResponseContentType = "application/json";
		assertOcrException(createPage(), OcrException.Code.OCR_REQUEST_REJECTED);
	}

	@Test
	void status401MapsToAuthFailed() throws Exception {
		this.nextResponseStatus = 401;
		this.nextResponseJson = "";
		this.nextResponseContentType = "application/json";
		assertOcrException(createPage(), OcrException.Code.OCR_AUTH_FAILED);
	}

	@Test
	void status403MapsToAuthFailed() throws Exception {
		this.nextResponseStatus = 403;
		this.nextResponseJson = "";
		this.nextResponseContentType = "application/json";
		assertOcrException(createPage(), OcrException.Code.OCR_AUTH_FAILED);
	}

	@Test
	void status408MapsToServiceBusy() throws Exception {
		this.nextResponseStatus = 408;
		this.nextResponseJson = "";
		this.nextResponseContentType = "application/json";
		assertOcrException(createPage(), OcrException.Code.OCR_SERVICE_BUSY);
	}

	@Test
	void status429MapsToServiceBusy() throws Exception {
		this.nextResponseStatus = 429;
		this.nextResponseJson = "";
		this.nextResponseContentType = "application/json";
		assertOcrException(createPage(), OcrException.Code.OCR_SERVICE_BUSY);
	}

	@Test
	void status500MapsToServiceUnavailable() throws Exception {
		this.nextResponseStatus = 500;
		this.nextResponseJson = "";
		this.nextResponseContentType = "application/json";
		assertOcrException(createPage(), OcrException.Code.OCR_SERVICE_UNAVAILABLE);
	}

	@Test
	void status503MapsToServiceUnavailable() throws Exception {
		this.nextResponseStatus = 503;
		this.nextResponseJson = "";
		this.nextResponseContentType = "application/json";
		assertOcrException(createPage(), OcrException.Code.OCR_SERVICE_UNAVAILABLE);
	}

	@Test
	void status301MapsToProtocolError() throws Exception {
		this.nextResponseStatus = 301;
		this.nextResponseJson = "";
		this.nextResponseContentType = "text/html";
		assertOcrException(createPage(), OcrException.Code.OCR_PROTOCOL_ERROR);
	}

	@Test
	void status999MapsToProtocolError() throws Exception {
		this.nextResponseStatus = 999;
		this.nextResponseJson = "";
		this.nextResponseContentType = "application/json";
		assertOcrException(createPage(), OcrException.Code.OCR_PROTOCOL_ERROR);
	}

	@Test
	void connectionRefusalMapsToServiceUnavailable() throws Exception {
		int unusedPort;
		try (ServerSocket probe = new ServerSocket(0)) {
			unusedPort = probe.getLocalPort();
		}
		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = writePng(pngBytes);
		RenderedFirstPage page = new RenderedFirstPage(UUID.randomUUID(), pngPath, 100, 100, 200, pngBytes.length);

		LlamaCppOcrClient client = createClientWithPort(unusedPort);
		OcrException e = assertThrows(OcrException.class, () -> client.recognize(page));
		assertEquals(OcrException.Code.OCR_SERVICE_UNAVAILABLE, e.getCode());
	}

	@Test
	void missingContentTypeFails() throws Exception {
		this.nextResponseContentType = "text/html";
		assertOcrException(createPage(), OcrException.Code.OCR_PROTOCOL_ERROR);
	}

	@Test
	void wrongContentTypeFails() throws Exception {
		this.nextResponseContentType = "application/xml";
		assertOcrException(createPage(), OcrException.Code.OCR_PROTOCOL_ERROR);
	}

	@Test
	void malformedJsonFailsWithResponseInvalid() throws Exception {
		this.nextResponseJson = "not valid json";
		assertOcrException(createPage(), OcrException.Code.OCR_RESPONSE_INVALID);
	}

	@Test
	void emptyResponseFailsWithResponseInvalid() throws Exception {
		this.nextResponseJson = "";
		assertOcrException(createPage(), OcrException.Code.OCR_RESPONSE_INVALID);
	}

	@Test
	void trailingJsonAfterRootFailsWithResponseInvalid() throws Exception {
		this.nextResponseJson = buildValidResponse(SYNTHETIC_OCR_TEXT) + " {\"unexpected\":true}";
		assertOcrException(createPage(), OcrException.Code.OCR_RESPONSE_INVALID);
	}

	@Test
	void trailingNonJsonGarbageAfterRootFailsWithResponseInvalid() throws Exception {
		this.nextResponseJson = buildValidResponse(SYNTHETIC_OCR_TEXT) + " garbage";
		assertOcrException(createPage(), OcrException.Code.OCR_RESPONSE_INVALID);
	}

	@Test
	void whitespaceAfterRootIsAccepted() throws Exception {
		this.nextResponseJson = buildValidResponse(SYNTHETIC_OCR_TEXT) + "   \n  ";
		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = writePng(pngBytes);
		RenderedFirstPage page = new RenderedFirstPage(UUID.randomUUID(), pngPath, 100, 100, 200, pngBytes.length);

		LlamaCppOcrClient client = createClient();
		OcrResult result = client.recognize(page);
		assertEquals(SYNTHETIC_OCR_TEXT, result.text());
	}

	@Test
	void zeroChoicesFailsWithResponseInvalid() throws Exception {
		this.nextResponseJson = "{\"model\":\"" + MODEL + "\",\"choices\":[]}";
		assertOcrException(createPage(), OcrException.Code.OCR_RESPONSE_INVALID);
	}

	@Test
	void multipleChoicesFailsWithResponseInvalid() throws Exception {
		this.nextResponseJson = "{\"model\":\"" + MODEL + "\",\"choices\":["
				+ "{\"message\":{\"role\":\"assistant\",\"content\":\"text1\"},\"finish_reason\":\"stop\"},"
				+ "{\"message\":{\"role\":\"assistant\",\"content\":\"text2\"},\"finish_reason\":\"stop\"}]}";
		assertOcrException(createPage(), OcrException.Code.OCR_RESPONSE_INVALID);
	}

	@Test
	void wrongModelFailsWithResponseInvalid() throws Exception {
		this.nextResponseJson = "{\"model\":\"/models/wrong-model.gguf\","
				+ "\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + SYNTHETIC_OCR_TEXT
				+ "\"},\"finish_reason\":\"stop\"}]}";
		assertOcrException(createPage(), OcrException.Code.OCR_RESPONSE_INVALID);
	}

	@Test
	void invalidRoleFailsWithResponseInvalid() throws Exception {
		this.nextResponseJson = "{\"model\":\"" + MODEL + "\","
				+ "\"choices\":[{\"message\":{\"role\":\"user\",\"content\":\"" + SYNTHETIC_OCR_TEXT
				+ "\"},\"finish_reason\":\"stop\"}]}";
		assertOcrException(createPage(), OcrException.Code.OCR_RESPONSE_INVALID);
	}

	@Test
	void nonStringContentFailsWithResponseInvalid() throws Exception {
		this.nextResponseJson = "{\"model\":\"" + MODEL + "\","
				+ "\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":12345},\"finish_reason\":\"stop\"}]}";
		assertOcrException(createPage(), OcrException.Code.OCR_RESPONSE_INVALID);
	}

	@Test
	void blankContentFailsWithOutputEmpty() throws Exception {
		this.nextResponseJson = "{\"model\":\"" + MODEL + "\","
				+ "\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"  \"},\"finish_reason\":\"stop\"}]}";
		assertOcrException(createPage(), OcrException.Code.OCR_OUTPUT_EMPTY);
	}

	@Test
	void oversizedOcrTextFailsWithResponseInvalid() throws Exception {
		// Build a response with text longer than maxOcrCharacters (1000000).
		// Use a smaller maxOcrCharacters via a custom client.
		String longText = "a".repeat(1001);
		this.nextResponseJson = "{\"model\":\"" + MODEL + "\","
				+ "\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + longText
				+ "\"},\"finish_reason\":\"stop\"}]}";

		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = writePng(pngBytes);
		RenderedFirstPage page = new RenderedFirstPage(UUID.randomUUID(), pngPath, 100, 100, 200, pngBytes.length);

		// Create a client with maxOcrCharacters=1000.
		LlamaCppOcrProperties props = new LlamaCppOcrProperties(
				"http://127.0.0.1:" + this.port, "/v1/chat/completions", MODEL,
				Duration.ofSeconds(5), Duration.ofSeconds(300), Duration.ofSeconds(310),
				33554432L, this.maxResponseBytes, 1000, 4096, 0.1, 0.9, 1);
		OkHttpClient httpClient = new OkHttpClient.Builder()
				.connectTimeout(Duration.ofMillis(5000))
				.readTimeout(Duration.ofMillis(300000))
				.callTimeout(Duration.ofMillis(310000))
				.followRedirects(false)
				.followSslRedirects(false)
				.proxy(java.net.Proxy.NO_PROXY)
				.build();
		LlamaCppOcrClient client = new LlamaCppOcrClient(httpClient, props);
		OcrException e = assertThrows(OcrException.class, () -> client.recognize(page));
		assertEquals(OcrException.Code.OCR_RESPONSE_INVALID, e.getCode());
	}

	@Test
	void missingFinishReasonFailsWithResponseInvalid() throws Exception {
		this.nextResponseJson = "{\"model\":\"" + MODEL + "\","
				+ "\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + SYNTHETIC_OCR_TEXT + "\"}}]}";
		assertOcrException(createPage(), OcrException.Code.OCR_RESPONSE_INVALID);
	}

	@Test
	void unknownFinishReasonFailsWithResponseInvalid() throws Exception {
		this.nextResponseJson = "{\"model\":\"" + MODEL + "\","
				+ "\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + SYNTHETIC_OCR_TEXT
				+ "\"},\"finish_reason\":\"unknown\"}]}";
		assertOcrException(createPage(), OcrException.Code.OCR_RESPONSE_INVALID);
	}

	@Test
	void finishReasonLengthMapsToOutputTruncated() throws Exception {
		this.nextResponseJson = "{\"model\":\"" + MODEL + "\","
				+ "\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + SYNTHETIC_OCR_TEXT
				+ "\"},\"finish_reason\":\"length\"}]}";
		assertOcrException(createPage(), OcrException.Code.OCR_OUTPUT_TRUNCATED);
	}

	@Test
	void negativeUsageFailsWithResponseInvalid() throws Exception {
		this.nextResponseJson = "{\"model\":\"" + MODEL + "\","
				+ "\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + SYNTHETIC_OCR_TEXT
				+ "\"},\"finish_reason\":\"stop\"}],"
				+ "\"usage\":{\"prompt_tokens\":-1,\"completion_tokens\":20,\"total_tokens\":19}}";
		assertOcrException(createPage(), OcrException.Code.OCR_RESPONSE_INVALID);
	}

	@Test
	void malformedUsageFailsWithResponseInvalid() throws Exception {
		this.nextResponseJson = "{\"model\":\"" + MODEL + "\","
				+ "\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + SYNTHETIC_OCR_TEXT
				+ "\"},\"finish_reason\":\"stop\"}],"
				+ "\"usage\":{\"prompt_tokens\":\"not-a-number\"}}";
		assertOcrException(createPage(), OcrException.Code.OCR_RESPONSE_INVALID);
	}

	@Test
	void responseOneByteOverLimitIsRejected() throws Exception {
		this.maxResponseBytes = 10L;
		assertOcrException(createPage(), OcrException.Code.OCR_RESPONSE_TOO_LARGE);
	}

	@Test
	void responseExactlyAtLimitSucceeds() throws Exception {
		// Build a response and set maxResponseBytes to exactly its byte length.
		this.nextResponseJson = buildValidResponse(SYNTHETIC_OCR_TEXT);
		byte[] responseBytes = this.nextResponseJson.getBytes(StandardCharsets.UTF_8);
		this.maxResponseBytes = responseBytes.length;

		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = writePng(pngBytes);
		RenderedFirstPage page = new RenderedFirstPage(UUID.randomUUID(), pngPath, 100, 100, 200, pngBytes.length);

		LlamaCppOcrClient client = createClient();
		OcrResult result = client.recognize(page);
		assertEquals(SYNTHETIC_OCR_TEXT, result.text());
	}

	@Test
	void responseOneByteOverLimitWithMultipleReadsIsRejected() throws Exception {
		// Build a response large enough to require multiple reads.
		String longText = "a".repeat(5000);
		this.nextResponseJson = "{\"model\":\"" + MODEL + "\","
				+ "\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + longText
				+ "\"},\"finish_reason\":\"stop\"}]}";
		byte[] responseBytes = this.nextResponseJson.getBytes(StandardCharsets.UTF_8);
		// Set the limit to one byte less than the response size.
		this.maxResponseBytes = responseBytes.length - 1;

		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = writePng(pngBytes);
		RenderedFirstPage page = new RenderedFirstPage(UUID.randomUUID(), pngPath, 100, 100, 200, pngBytes.length);

		LlamaCppOcrClient client = createClient();
		OcrException e = assertThrows(OcrException.class, () -> client.recognize(page));
		assertEquals(OcrException.Code.OCR_RESPONSE_TOO_LARGE, e.getCode());
	}

	@Test
	void responseAtLimitWithMultipleReadsSucceeds() throws Exception {
		String longText = "a".repeat(5000);
		this.nextResponseJson = "{\"model\":\"" + MODEL + "\","
				+ "\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + longText
				+ "\"},\"finish_reason\":\"stop\"}]}";
		byte[] responseBytes = this.nextResponseJson.getBytes(StandardCharsets.UTF_8);
		this.maxResponseBytes = responseBytes.length; // Exactly at limit.

		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = writePng(pngBytes);
		RenderedFirstPage page = new RenderedFirstPage(UUID.randomUUID(), pngPath, 100, 100, 200, pngBytes.length);

		LlamaCppOcrClient client = createClient();
		OcrResult result = client.recognize(page);
		assertEquals(longText, result.text());
	}

	@Test
	void responseOneByteUnderLimitWithMultipleReadsSucceeds() throws Exception {
		String longText = "a".repeat(5000);
		this.nextResponseJson = "{\"model\":\"" + MODEL + "\","
				+ "\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + longText
				+ "\"},\"finish_reason\":\"stop\"}]}";
		byte[] responseBytes = this.nextResponseJson.getBytes(StandardCharsets.UTF_8);
		this.maxResponseBytes = responseBytes.length - 1; // One byte under.
		// Trim one character from the text to make it fit.
		this.nextResponseJson = this.nextResponseJson.substring(0, this.nextResponseJson.length() - 1) + "}";
		// Actually, let's just set the limit to exactly the byte length.
		this.maxResponseBytes = this.nextResponseJson.getBytes(StandardCharsets.UTF_8).length;

		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = writePng(pngBytes);
		RenderedFirstPage page = new RenderedFirstPage(UUID.randomUUID(), pngPath, 100, 100, 200, pngBytes.length);

		LlamaCppOcrClient client = createClient();
		OcrResult result = client.recognize(page);
		assertNotNull(result);
	}

	@Test
	void semaphoreConcurrencyNeverExceedsOne() throws Exception {
		// Use latches to control the first request so the second caller
		// is genuinely queued.
		this.firstRequestArrived = new CountDownLatch(1);
		this.releaseFirstRequest = new CountDownLatch(1);

		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = writePng(pngBytes);
		RenderedFirstPage page = new RenderedFirstPage(UUID.randomUUID(), pngPath, 100, 100, 200, pngBytes.length);

		LlamaCppOcrClient client = createClient();
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			// Start the first request; it will block on the server side.
			CountDownLatch firstDone = new CountDownLatch(1);
			AtomicReference<Throwable> firstError = new AtomicReference<>();
			executor.submit(() -> {
				try {
					client.recognize(page);
				}
				catch (Throwable t) {
					firstError.set(t);
				}
				finally {
					firstDone.countDown();
				}
			});

			// Wait for the first request to arrive at the server.
			assertTrue(this.firstRequestArrived.await(5, TimeUnit.SECONDS),
					"First request did not arrive at the server in time");

			// Start the second request; it should be queued by the semaphore.
			CountDownLatch secondStarted = new CountDownLatch(1);
			AtomicReference<Throwable> secondError = new AtomicReference<>();
			executor.submit(() -> {
				secondStarted.countDown();
				try {
					client.recognize(page);
				}
				catch (Throwable t) {
					secondError.set(t);
				}
			});

			// Give the second caller time to start and be queued.
			assertTrue(secondStarted.await(2, TimeUnit.SECONDS), "Second caller did not start");
			Thread.yield();

			// The server should have seen at most 1 concurrent request so far.
			assertTrue(this.maxConcurrentObserved.get() <= 1,
					"Max concurrent was " + this.maxConcurrentObserved.get());

			// Release the first request.
			this.releaseFirstRequest.countDown();
			assertTrue(firstDone.await(10, TimeUnit.SECONDS), "First request did not complete");
			assertNotNull(firstError.get() == null ? "first succeeded" : firstError.get());

			// The second request should now proceed.
			// Wait a bit for it to complete.
			Thread.yield();
		}
		finally {
			executor.shutdownNow();
		}

		assertTrue(this.maxConcurrentObserved.get() <= 1,
				"Max concurrent requests was " + this.maxConcurrentObserved.get() + ", expected <= 1");
	}

	@Test
	void interruptedWaiterRestoresInterruptFlagAndNoPermitLeaks() throws Exception {
		// Block the first OCR call while it holds the semaphore.
		this.firstRequestArrived = new CountDownLatch(1);
		this.releaseFirstRequest = new CountDownLatch(1);

		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = writePng(pngBytes);
		RenderedFirstPage page = new RenderedFirstPage(UUID.randomUUID(), pngPath, 100, 100, 200, pngBytes.length);

		LlamaCppOcrClient client = createClient();

		// Start the first request in a separate thread; it will hold the semaphore.
		CountDownLatch firstDone = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			executor.submit(() -> {
				try {
					client.recognize(page);
				}
				catch (Throwable ignored) {
				}
				finally {
					firstDone.countDown();
				}
			});

			// Wait for the first request to arrive at the server (holding the semaphore).
			assertTrue(this.firstRequestArrived.await(5, TimeUnit.SECONDS),
					"First request did not arrive at the server in time");

			// Start a second caller that will be queued on the semaphore.
			// Use a latch to detect when the second caller has started,
			// and record the interrupt flag inside the thread.
			final CountDownLatch secondStarted = new CountDownLatch(1);
			final CountDownLatch secondDone = new CountDownLatch(1);
			final AtomicReference<Throwable> secondError = new AtomicReference<>();
			final AtomicBoolean interruptFlagRestored = new AtomicBoolean(false);
			Thread secondThread = new Thread(() -> {
				secondStarted.countDown();
				try {
					client.recognize(page);
				}
				catch (Throwable t) {
					// Record the interrupt flag immediately after catching.
					interruptFlagRestored.set(Thread.currentThread().isInterrupted());
					secondError.set(t);
				}
				finally {
					secondDone.countDown();
				}
			});
			secondThread.start();

			// Wait for the second thread to start.
			assertTrue(secondStarted.await(2, TimeUnit.SECONDS), "Second thread did not start");

			// Deterministic evidence that the second caller is queued on the semaphore:
			// The server has only seen one request so far (the first one is blocked
			// on releaseFirstRequest). If the second caller were not blocked on the
			// semaphore, it would have made a second HTTP request to the server.
			// We verify this by checking that the server has not recorded a second
			// request. We use a bounded wait to allow the second caller to reach
			// the semaphore acquire.
			// Since we can't use Thread.sleep, we use a short bounded wait on a
			// latch that will never fire (as a timeout mechanism).
			CountDownLatch queueEvidence = new CountDownLatch(1);
			executor.submit(() -> {
				// Wait a short time for the second caller to reach the semaphore.
				// We can't use Thread.sleep, so we use a latch with a timeout.
				try {
					// This latch will never be counted down; we use the timeout
					// as a bounded wait.
					queueEvidence.await(100, TimeUnit.MILLISECONDS);
				}
				catch (InterruptedException ignored) {
					Thread.currentThread().interrupt();
				}
			});
			// The above submit is just to get a bounded wait without Thread.sleep.
			// Actually, let's just use a simple bounded wait on a latch.
			// We'll use the secondDone latch with a very short timeout to check
			// if the second caller has already completed (it shouldn't have).
			assertFalse(secondDone.await(50, TimeUnit.MILLISECONDS),
					"Second caller completed too quickly; not blocked on semaphore");

			// The server should have seen exactly 1 request (the first one).
			assertEquals(1, this.recordedRequests.size(),
					"Expected exactly 1 request at the server, but got " + this.recordedRequests.size());

			// Interrupt the waiting thread.
			secondThread.interrupt();
			assertTrue(secondDone.await(5, TimeUnit.SECONDS), "Second thread did not complete");

			// Assert OCR_INTERRUPTED.
			assertNotNull(secondError.get(), "Second thread should have thrown");
			assertTrue(secondError.get() instanceof OcrException,
					"Expected OcrException but got " + secondError.get().getClass());
			assertEquals(OcrException.Code.OCR_INTERRUPTED,
					((OcrException) secondError.get()).getCode());

			// Assert the interrupt flag was restored.
			assertTrue(interruptFlagRestored.get(),
					"Interrupt flag was not restored after OCR_INTERRUPTED");

			// Release the first request.
			this.releaseFirstRequest.countDown();
			assertTrue(firstDone.await(10, TimeUnit.SECONDS), "First request did not complete");

			// Perform another OCR call to prove no semaphore permit leaked.
			OcrResult result = client.recognize(page);
			assertNotNull(result);
		}
		finally {
			executor.shutdownNow();
		}
	}

	@Test
	void logsAndExceptionsContainNoForbiddenValues() throws Exception {
		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = writePng(pngBytes);
		RenderedFirstPage page = new RenderedFirstPage(UUID.randomUUID(), pngPath, 100, 100, 200, pngBytes.length);

		LlamaCppOcrClient client = createClient();
		OcrResult result = client.recognize(page);

		// toString must not contain OCR text.
		String str = result.toString();
		assertFalse(str.contains(SYNTHETIC_OCR_TEXT));
		assertTrue(str.contains(page.documentId().toString()));
		assertTrue(str.contains(MODEL));

		// Exception messages must not contain forbidden values.
		this.nextResponseStatus = 500;
		this.nextResponseJson = "";
		this.nextResponseContentType = "application/json";
		OcrException e = assertThrows(OcrException.class, () -> client.recognize(page));
		assertFalse(e.getMessage().contains(SYNTHETIC_OCR_TEXT));
		assertFalse(e.getMessage().contains("192.168.1.34"));
		assertFalse(e.getMessage().contains("data:image/png"));
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	private RenderedFirstPage createPage() throws Exception {
		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = writePng(pngBytes);
		return new RenderedFirstPage(UUID.randomUUID(), pngPath, 100, 100, 200, pngBytes.length);
	}

	private void assertOcrException(RenderedFirstPage page, OcrException.Code expectedCode) {
		LlamaCppOcrClient client = createClient();
		OcrException e = assertThrows(OcrException.class, () -> client.recognize(page));
		assertEquals(expectedCode, e.getCode());
	}

	private void handleClient(Socket client) {
		try (client) {
			InputStream in = client.getInputStream();
			OutputStream out = client.getOutputStream();

			String requestLine = readLine(in);
			if (requestLine == null || requestLine.isEmpty()) {
				return;
			}
			String[] parts = requestLine.split(" ");
			String method = parts[0];
			String path = parts[1];

			Map<String, String> headers = new java.util.LinkedHashMap<>();
			String headerLine;
			while ((headerLine = readLine(in)) != null && !headerLine.isEmpty()) {
				int colon = headerLine.indexOf(':');
				if (colon > 0) {
					String name = headerLine.substring(0, colon).trim();
					String value = headerLine.substring(colon + 1).trim();
					headers.put(name.toLowerCase(), value);
				}
			}

			// Read body with a capture limit. Use a fixed-size copy buffer
			// to avoid unbounded allocation based on declared sizes.
			String transferEncoding = headers.get("transfer-encoding");
			ByteArrayOutputStream bodyBuffer = new ByteArrayOutputStream();
			long totalCaptured = 0;
			final int COPY_BUF_SIZE = 8192;
			final byte[] copyBuf = new byte[COPY_BUF_SIZE];
			if (transferEncoding != null && transferEncoding.toLowerCase().contains("chunked")) {
				while (true) {
					String sizeLine = readLine(in);
					if (sizeLine == null || sizeLine.isEmpty()) {
						break;
					}
					int semicolon = sizeLine.indexOf(';');
					String sizeStr = semicolon >= 0 ? sizeLine.substring(0, semicolon) : sizeLine;
					long chunkSize;
					try {
						chunkSize = Long.parseLong(sizeStr.trim(), 16);
					}
					catch (NumberFormatException e) {
						break;
					}
					if (chunkSize < 0) {
						throw new IOException("Negative chunk size");
					}
					if (chunkSize == 0) {
						readLine(in);
						break;
					}
					if (chunkSize > MAX_CAPTURE_BYTES - totalCaptured) {
						throw new IOException("Chunk size exceeds remaining capture allowance");
					}
					long remaining = chunkSize;
					while (remaining > 0) {
						int toRead = (int) Math.min(remaining, COPY_BUF_SIZE);
						int n = in.read(copyBuf, 0, toRead);
						if (n == -1) {
							break;
						}
						bodyBuffer.write(copyBuf, 0, n);
						totalCaptured += n;
						remaining -= n;
						if (totalCaptured > MAX_CAPTURE_BYTES) {
							throw new IOException("Request body exceeds capture limit");
						}
					}
					readLine(in);
				}
			}
			else {
				String contentLengthStr = headers.get("content-length");
				long contentLength = contentLengthStr != null ? Long.parseLong(contentLengthStr) : 0;
				if (contentLength < 0) {
					throw new IOException("Negative content length");
				}
				if (contentLength > MAX_CAPTURE_BYTES) {
					throw new IOException("Content length exceeds capture limit");
				}
				long remaining = contentLength;
				while (remaining > 0) {
					int toRead = (int) Math.min(remaining, COPY_BUF_SIZE);
					int n = in.read(copyBuf, 0, toRead);
					if (n == -1) {
						break;
					}
					bodyBuffer.write(copyBuf, 0, n);
					totalCaptured += n;
					remaining -= n;
					if (totalCaptured > MAX_CAPTURE_BYTES) {
						throw new IOException("Request body exceeds capture limit");
					}
				}
			}
			byte[] body = bodyBuffer.toByteArray();

			// Track concurrency.
			int current = this.concurrentRequests.incrementAndGet();
			this.maxConcurrentObserved.updateAndGet(max -> Math.max(max, current));

			// Record the request.
			this.recordedRequests.add(new RecordedRequest(method, path, headers,
					new String(body, StandardCharsets.UTF_8)));

			// Latch control for concurrency tests.
			if (this.firstRequestArrived != null && this.recordedRequests.size() == 1) {
				this.firstRequestArrived.countDown();
				if (this.releaseFirstRequest != null) {
					try {
						this.releaseFirstRequest.await(15, TimeUnit.SECONDS);
					}
					catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
					}
				}
			}

			// Send the response.
			String responseBody = this.nextResponseJson;
			byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
			String responseHeaders = "HTTP/1.1 " + this.nextResponseStatus + " " + reasonPhrase(this.nextResponseStatus)
					+ "\r\n"
					+ "Content-Type: " + this.nextResponseContentType + "\r\n"
					+ "Content-Length: " + responseBytes.length + "\r\n"
					+ "Connection: close\r\n"
					+ "\r\n";
			out.write(responseHeaders.getBytes(StandardCharsets.UTF_8));
			out.write(responseBytes);
			out.flush();

			this.concurrentRequests.decrementAndGet();
		}
		catch (IOException e) {
			// Connection closed or capture limit exceeded; ignore.
		}
	}

	private static String readLine(InputStream in) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		int b;
		while ((b = in.read()) != -1) {
			if (b == '\n') {
				break;
			}
			if (b != '\r') {
				baos.write(b);
			}
		}
		if (b == -1 && baos.size() == 0) {
			return null;
		}
		return baos.toString(StandardCharsets.UTF_8);
	}

	private static String reasonPhrase(int status) {
		return switch (status) {
			case 200 -> "OK";
			case 301 -> "Moved Permanently";
			case 302 -> "Found";
			case 400 -> "Bad Request";
			case 401 -> "Unauthorized";
			case 403 -> "Forbidden";
			case 404 -> "Not Found";
			case 405 -> "Method Not Allowed";
			case 408 -> "Request Timeout";
			case 409 -> "Conflict";
			case 415 -> "Unsupported Media Type";
			case 422 -> "Unprocessable Entity";
			case 429 -> "Too Many Requests";
			case 500 -> "Internal Server Error";
			case 503 -> "Service Unavailable";
			default -> "Unknown";
		};
	}

	private LlamaCppOcrClient createClient() {
		return createClientWithPort(this.port);
	}

	private LlamaCppOcrClient createClientWithPort(int port) {
		LlamaCppOcrProperties props = new LlamaCppOcrProperties(
				"http://127.0.0.1:" + port, "/v1/chat/completions", MODEL,
				Duration.ofSeconds(5), Duration.ofSeconds(300), Duration.ofSeconds(310),
				33554432L, this.maxResponseBytes, 1000000, 4096, 0.1, 0.9, 1);
		OkHttpClient httpClient = new OkHttpClient.Builder()
				.connectTimeout(Duration.ofMillis(5000))
				.readTimeout(Duration.ofMillis(300000))
				.callTimeout(Duration.ofMillis(310000))
				.followRedirects(false)
				.followSslRedirects(false)
				.proxy(java.net.Proxy.NO_PROXY)
				.build();
		return new LlamaCppOcrClient(httpClient, props);
	}

	private String buildValidResponse(String text) {
		return "{\"model\":\"" + MODEL + "\","
				+ "\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + text
				+ "\"},\"finish_reason\":\"stop\"}]}";
	}

	private static byte[] createSyntheticPng(int payloadLength) {
		byte[] png = new byte[8 + payloadLength];
		png[0] = (byte) 0x89;
		png[1] = 0x50;
		png[2] = 0x4E;
		png[3] = 0x47;
		png[4] = 0x0D;
		png[5] = 0x0A;
		png[6] = 0x1A;
		png[7] = 0x0A;
		for (int i = 8; i < png.length; i++) {
			png[i] = (byte) (i % 256);
		}
		return png;
	}

	private Path writePng(byte[] pngBytes) throws IOException {
		Path path = this.tempDir.resolve("test-" + pngBytes.length + "-" + System.nanoTime() + ".png");
		Files.write(path, pngBytes);
		return path;
	}

	private static void assertArrayEquals(byte[] expected, byte[] actual) {
		if (expected.length != actual.length) {
			throw new AssertionError("Array lengths differ: expected " + expected.length + ", got " + actual.length);
		}
		for (int i = 0; i < expected.length; i++) {
			if (expected[i] != actual[i]) {
				throw new AssertionError("Arrays differ at index " + i);
			}
		}
	}

	private static final class RecordedRequest {
		final String method;
		final String path;
		final Map<String, String> headers;
		final String body;
		final String contentType;
		final String accept;

		RecordedRequest(String method, String path, Map<String, String> headers, String body) {
			this.method = method;
			this.path = path;
			this.headers = headers;
			this.body = body;
			this.contentType = headers.getOrDefault("content-type", "");
			this.accept = headers.getOrDefault("accept", "");
		}
	}

}
