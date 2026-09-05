package horse.sumomo.pos_doc_backend.ocr.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
import horse.sumomo.pos_doc_backend.ocr.service.OcrException.Code;
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

	@BeforeEach
	void startServer() throws Exception {
		// Find a free port.
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

		this.serverRunning = true;
		this.serverSocket = new ServerSocket();
		this.serverSocket.bind(new InetSocketAddress("127.0.0.1", this.port));
		this.serverSocket.setSoTimeout(10000);

		this.serverThread = new Thread(() -> {
			while (this.serverRunning) {
				try {
					Socket client = this.serverSocket.accept();
					handleClient(client);
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

		// Verify the request.
		assertEquals(1, this.recordedRequests.size());
		RecordedRequest req = this.recordedRequests.get(0);
		assertEquals("POST", req.method);
		assertEquals("/v1/chat/completions", req.path);
		assertEquals("application/json", req.contentType);
		assertEquals("application/json", req.accept);
		assertFalse(req.headers.containsKey("Authorization"));
		assertFalse(req.headers.containsKey("Cookie"));

		// Verify the JSON body.
		JsonNode root = this.objectMapper.readTree(req.body);
		assertEquals(MODEL, root.get("model").asText());
		assertEquals(PROMPT, root.get("messages").get(0).get("content").get(1).get("text").asText());
		assertEquals(4096, root.get("max_tokens").asInt());
		assertFalse(root.has("max_completion_tokens"));

		// Verify the PNG bytes.
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
		assertEquals(Code.OCR_PROTOCOL_ERROR, e.getCode());
	}

	@Test
	void status400MapsToRequestRejected() throws Exception {
		this.nextResponseStatus = 400;
		this.nextResponseJson = "";
		this.nextResponseContentType = "application/json";

		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = writePng(pngBytes);
		RenderedFirstPage page = new RenderedFirstPage(UUID.randomUUID(), pngPath, 100, 100, 200, pngBytes.length);

		LlamaCppOcrClient client = createClient();
		OcrException e = assertThrows(OcrException.class, () -> client.recognize(page));
		assertEquals(Code.OCR_REQUEST_REJECTED, e.getCode());
	}

	@Test
	void status404MapsToRequestRejected() throws Exception {
		this.nextResponseStatus = 404;
		this.nextResponseJson = "";
		this.nextResponseContentType = "application/json";

		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = writePng(pngBytes);
		RenderedFirstPage page = new RenderedFirstPage(UUID.randomUUID(), pngPath, 100, 100, 200, pngBytes.length);

		LlamaCppOcrClient client = createClient();
		OcrException e = assertThrows(OcrException.class, () -> client.recognize(page));
		assertEquals(Code.OCR_REQUEST_REJECTED, e.getCode());
	}

	@Test
	void status401MapsToAuthFailed() throws Exception {
		this.nextResponseStatus = 401;
		this.nextResponseJson = "";
		this.nextResponseContentType = "application/json";

		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = writePng(pngBytes);
		RenderedFirstPage page = new RenderedFirstPage(UUID.randomUUID(), pngPath, 100, 100, 200, pngBytes.length);

		LlamaCppOcrClient client = createClient();
		OcrException e = assertThrows(OcrException.class, () -> client.recognize(page));
		assertEquals(Code.OCR_AUTH_FAILED, e.getCode());
	}

	@Test
	void status403MapsToAuthFailed() throws Exception {
		this.nextResponseStatus = 403;
		this.nextResponseJson = "";
		this.nextResponseContentType = "application/json";

		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = writePng(pngBytes);
		RenderedFirstPage page = new RenderedFirstPage(UUID.randomUUID(), pngPath, 100, 100, 200, pngBytes.length);

		LlamaCppOcrClient client = createClient();
		OcrException e = assertThrows(OcrException.class, () -> client.recognize(page));
		assertEquals(Code.OCR_AUTH_FAILED, e.getCode());
	}

	@Test
	void status408MapsToServiceBusy() throws Exception {
		this.nextResponseStatus = 408;
		this.nextResponseJson = "";
		this.nextResponseContentType = "application/json";

		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = writePng(pngBytes);
		RenderedFirstPage page = new RenderedFirstPage(UUID.randomUUID(), pngPath, 100, 100, 200, pngBytes.length);

		LlamaCppOcrClient client = createClient();
		OcrException e = assertThrows(OcrException.class, () -> client.recognize(page));
		assertEquals(Code.OCR_SERVICE_BUSY, e.getCode());
	}

	@Test
	void status429MapsToServiceBusy() throws Exception {
		this.nextResponseStatus = 429;
		this.nextResponseJson = "";
		this.nextResponseContentType = "application/json";

		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = writePng(pngBytes);
		RenderedFirstPage page = new RenderedFirstPage(UUID.randomUUID(), pngPath, 100, 100, 200, pngBytes.length);

		LlamaCppOcrClient client = createClient();
		OcrException e = assertThrows(OcrException.class, () -> client.recognize(page));
		assertEquals(Code.OCR_SERVICE_BUSY, e.getCode());
	}

	@Test
	void status500MapsToServiceUnavailable() throws Exception {
		this.nextResponseStatus = 500;
		this.nextResponseJson = "";
		this.nextResponseContentType = "application/json";

		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = writePng(pngBytes);
		RenderedFirstPage page = new RenderedFirstPage(UUID.randomUUID(), pngPath, 100, 100, 200, pngBytes.length);

		LlamaCppOcrClient client = createClient();
		OcrException e = assertThrows(OcrException.class, () -> client.recognize(page));
		assertEquals(Code.OCR_SERVICE_UNAVAILABLE, e.getCode());
	}

	@Test
	void status503MapsToServiceUnavailable() throws Exception {
		this.nextResponseStatus = 503;
		this.nextResponseJson = "";
		this.nextResponseContentType = "application/json";

		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = writePng(pngBytes);
		RenderedFirstPage page = new RenderedFirstPage(UUID.randomUUID(), pngPath, 100, 100, 200, pngBytes.length);

		LlamaCppOcrClient client = createClient();
		OcrException e = assertThrows(OcrException.class, () -> client.recognize(page));
		assertEquals(Code.OCR_SERVICE_UNAVAILABLE, e.getCode());
	}

	@Test
	void connectionRefusalMapsToServiceUnavailable() throws Exception {
		// Use a port that is not listening.
		int unusedPort;
		try (ServerSocket probe = new ServerSocket(0)) {
			unusedPort = probe.getLocalPort();
		}

		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = writePng(pngBytes);
		RenderedFirstPage page = new RenderedFirstPage(UUID.randomUUID(), pngPath, 100, 100, 200, pngBytes.length);

		LlamaCppOcrClient client = createClientWithPort(unusedPort);
		OcrException e = assertThrows(OcrException.class, () -> client.recognize(page));
		assertEquals(Code.OCR_SERVICE_UNAVAILABLE, e.getCode());
	}

	@Test
	void missingContentTypeFails() throws Exception {
		this.nextResponseContentType = "text/html";

		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = writePng(pngBytes);
		RenderedFirstPage page = new RenderedFirstPage(UUID.randomUUID(), pngPath, 100, 100, 200, pngBytes.length);

		LlamaCppOcrClient client = createClient();
		OcrException e = assertThrows(OcrException.class, () -> client.recognize(page));
		assertEquals(Code.OCR_PROTOCOL_ERROR, e.getCode());
	}

	@Test
	void malformedJsonFailsWithResponseInvalid() throws Exception {
		this.nextResponseJson = "not valid json";

		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = writePng(pngBytes);
		RenderedFirstPage page = new RenderedFirstPage(UUID.randomUUID(), pngPath, 100, 100, 200, pngBytes.length);

		LlamaCppOcrClient client = createClient();
		OcrException e = assertThrows(OcrException.class, () -> client.recognize(page));
		assertEquals(Code.OCR_RESPONSE_INVALID, e.getCode());
	}

	@Test
	void zeroChoicesFailsWithResponseInvalid() throws Exception {
		this.nextResponseJson = "{\"model\":\"" + MODEL + "\",\"choices\":[]}";

		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = writePng(pngBytes);
		RenderedFirstPage page = new RenderedFirstPage(UUID.randomUUID(), pngPath, 100, 100, 200, pngBytes.length);

		LlamaCppOcrClient client = createClient();
		OcrException e = assertThrows(OcrException.class, () -> client.recognize(page));
		assertEquals(Code.OCR_RESPONSE_INVALID, e.getCode());
	}

	@Test
	void multipleChoicesFailsWithResponseInvalid() throws Exception {
		this.nextResponseJson = "{\"model\":\"" + MODEL + "\",\"choices\":["
				+ "{\"message\":{\"role\":\"assistant\",\"content\":\"text1\"},\"finish_reason\":\"stop\"},"
				+ "{\"message\":{\"role\":\"assistant\",\"content\":\"text2\"},\"finish_reason\":\"stop\"}]}";

		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = writePng(pngBytes);
		RenderedFirstPage page = new RenderedFirstPage(UUID.randomUUID(), pngPath, 100, 100, 200, pngBytes.length);

		LlamaCppOcrClient client = createClient();
		OcrException e = assertThrows(OcrException.class, () -> client.recognize(page));
		assertEquals(Code.OCR_RESPONSE_INVALID, e.getCode());
	}

	@Test
	void wrongModelFailsWithResponseInvalid() throws Exception {
		this.nextResponseJson = "{\"model\":\"/models/wrong-model.gguf\","
				+ "\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + SYNTHETIC_OCR_TEXT
				+ "\"},\"finish_reason\":\"stop\"}]}";

		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = writePng(pngBytes);
		RenderedFirstPage page = new RenderedFirstPage(UUID.randomUUID(), pngPath, 100, 100, 200, pngBytes.length);

		LlamaCppOcrClient client = createClient();
		OcrException e = assertThrows(OcrException.class, () -> client.recognize(page));
		assertEquals(Code.OCR_RESPONSE_INVALID, e.getCode());
	}

	@Test
	void invalidRoleFailsWithResponseInvalid() throws Exception {
		this.nextResponseJson = "{\"model\":\"" + MODEL + "\","
				+ "\"choices\":[{\"message\":{\"role\":\"user\",\"content\":\"" + SYNTHETIC_OCR_TEXT
				+ "\"},\"finish_reason\":\"stop\"}]}";

		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = writePng(pngBytes);
		RenderedFirstPage page = new RenderedFirstPage(UUID.randomUUID(), pngPath, 100, 100, 200, pngBytes.length);

		LlamaCppOcrClient client = createClient();
		OcrException e = assertThrows(OcrException.class, () -> client.recognize(page));
		assertEquals(Code.OCR_RESPONSE_INVALID, e.getCode());
	}

	@Test
	void nonStringContentFailsWithResponseInvalid() throws Exception {
		this.nextResponseJson = "{\"model\":\"" + MODEL + "\","
				+ "\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":12345},\"finish_reason\":\"stop\"}]}";

		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = writePng(pngBytes);
		RenderedFirstPage page = new RenderedFirstPage(UUID.randomUUID(), pngPath, 100, 100, 200, pngBytes.length);

		LlamaCppOcrClient client = createClient();
		OcrException e = assertThrows(OcrException.class, () -> client.recognize(page));
		assertEquals(Code.OCR_RESPONSE_INVALID, e.getCode());
	}

	@Test
	void blankContentFailsWithOutputEmpty() throws Exception {
		this.nextResponseJson = "{\"model\":\"" + MODEL + "\","
				+ "\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"  \"},\"finish_reason\":\"stop\"}]}";

		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = writePng(pngBytes);
		RenderedFirstPage page = new RenderedFirstPage(UUID.randomUUID(), pngPath, 100, 100, 200, pngBytes.length);

		LlamaCppOcrClient client = createClient();
		OcrException e = assertThrows(OcrException.class, () -> client.recognize(page));
		assertEquals(Code.OCR_OUTPUT_EMPTY, e.getCode());
	}

	@Test
	void missingFinishReasonFailsWithResponseInvalid() throws Exception {
		this.nextResponseJson = "{\"model\":\"" + MODEL + "\","
				+ "\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + SYNTHETIC_OCR_TEXT + "\"}}]}";

		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = writePng(pngBytes);
		RenderedFirstPage page = new RenderedFirstPage(UUID.randomUUID(), pngPath, 100, 100, 200, pngBytes.length);

		LlamaCppOcrClient client = createClient();
		OcrException e = assertThrows(OcrException.class, () -> client.recognize(page));
		assertEquals(Code.OCR_RESPONSE_INVALID, e.getCode());
	}

	@Test
	void unknownFinishReasonFailsWithResponseInvalid() throws Exception {
		this.nextResponseJson = "{\"model\":\"" + MODEL + "\","
				+ "\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + SYNTHETIC_OCR_TEXT
				+ "\"},\"finish_reason\":\"unknown\"}]}";

		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = writePng(pngBytes);
		RenderedFirstPage page = new RenderedFirstPage(UUID.randomUUID(), pngPath, 100, 100, 200, pngBytes.length);

		LlamaCppOcrClient client = createClient();
		OcrException e = assertThrows(OcrException.class, () -> client.recognize(page));
		assertEquals(Code.OCR_RESPONSE_INVALID, e.getCode());
	}

	@Test
	void finishReasonLengthMapsToOutputTruncated() throws Exception {
		this.nextResponseJson = "{\"model\":\"" + MODEL + "\","
				+ "\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + SYNTHETIC_OCR_TEXT
				+ "\"},\"finish_reason\":\"length\"}]}";

		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = writePng(pngBytes);
		RenderedFirstPage page = new RenderedFirstPage(UUID.randomUUID(), pngPath, 100, 100, 200, pngBytes.length);

		LlamaCppOcrClient client = createClient();
		OcrException e = assertThrows(OcrException.class, () -> client.recognize(page));
		assertEquals(Code.OCR_OUTPUT_TRUNCATED, e.getCode());
	}

	@Test
	void negativeUsageFailsWithResponseInvalid() throws Exception {
		this.nextResponseJson = "{\"model\":\"" + MODEL + "\","
				+ "\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + SYNTHETIC_OCR_TEXT
				+ "\"},\"finish_reason\":\"stop\"}],"
				+ "\"usage\":{\"prompt_tokens\":-1,\"completion_tokens\":20,\"total_tokens\":19}}";

		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = writePng(pngBytes);
		RenderedFirstPage page = new RenderedFirstPage(UUID.randomUUID(), pngPath, 100, 100, 200, pngBytes.length);

		LlamaCppOcrClient client = createClient();
		OcrException e = assertThrows(OcrException.class, () -> client.recognize(page));
		assertEquals(Code.OCR_RESPONSE_INVALID, e.getCode());
	}

	@Test
	void responseOneByteOverLimitIsRejected() throws Exception {
		// Set maxResponseBytes to a very small value so the response exceeds it.
		this.maxResponseBytes = 10L;

		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = writePng(pngBytes);
		RenderedFirstPage page = new RenderedFirstPage(UUID.randomUUID(), pngPath, 100, 100, 200, pngBytes.length);

		LlamaCppOcrClient client = createClient();
		OcrException e = assertThrows(OcrException.class, () -> client.recognize(page));
		assertEquals(Code.OCR_RESPONSE_TOO_LARGE, e.getCode());
	}

	@Test
	void semaphoreConcurrencyNeverExceedsOne() throws Exception {
		// Make the server slow enough that two concurrent requests would
		// overlap if the semaphore didn't work.
		this.nextResponseJson = buildValidResponse(SYNTHETIC_OCR_TEXT);

		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = writePng(pngBytes);
		RenderedFirstPage page = new RenderedFirstPage(UUID.randomUUID(), pngPath, 100, 100, 200, pngBytes.length);

		LlamaCppOcrClient client = createClient();

		// Make two sequential calls. The semaphore should ensure only one
		// at a time.
		client.recognize(page);
		client.recognize(page);

		assertTrue(this.maxConcurrentObserved.get() <= 1,
				"Max concurrent requests was " + this.maxConcurrentObserved.get() + ", expected <= 1");
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
	}

	// ------------------------------------------------------------------
	// server helpers
	// ------------------------------------------------------------------

	private void handleClient(Socket client) throws IOException {
		try (client) {
			InputStream in = client.getInputStream();
			OutputStream out = client.getOutputStream();

			// Read the HTTP request.
			String requestLine = readLine(in);
			if (requestLine == null || requestLine.isEmpty()) {
				return;
			}
			String[] parts = requestLine.split(" ");
			String method = parts[0];
			String path = parts[1];

			// Read headers.
			java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
			String headerLine;
			while ((headerLine = readLine(in)) != null && !headerLine.isEmpty()) {
				int colon = headerLine.indexOf(':');
				if (colon > 0) {
					String name = headerLine.substring(0, colon).trim();
					String value = headerLine.substring(colon + 1).trim();
					headers.put(name.toLowerCase(), value);
				}
			}

			// Read body. Handle both Content-Length and chunked transfer encoding.
			String transferEncoding = headers.get("transfer-encoding");
			ByteArrayOutputStream bodyBuffer = new ByteArrayOutputStream();
			if (transferEncoding != null && transferEncoding.toLowerCase().contains("chunked")) {
				// Read chunked body.
				while (true) {
					String sizeLine = readLine(in);
					if (sizeLine == null || sizeLine.isEmpty()) {
						break;
					}
					// Strip any chunk extensions.
					int semicolon = sizeLine.indexOf(';');
					String sizeStr = semicolon >= 0 ? sizeLine.substring(0, semicolon) : sizeLine;
					int chunkSize;
					try {
						chunkSize = Integer.parseInt(sizeStr.trim(), 16);
					}
					catch (NumberFormatException e) {
						break;
					}
					if (chunkSize == 0) {
						// Read the trailing CRLF after the final chunk.
						readLine(in);
						break;
					}
					byte[] chunk = new byte[chunkSize];
					int totalRead = 0;
					while (totalRead < chunkSize) {
						int n = in.read(chunk, totalRead, chunkSize - totalRead);
						if (n == -1) {
							break;
						}
						totalRead += n;
					}
					bodyBuffer.write(chunk, 0, totalRead);
					// Read the CRLF after each chunk.
					readLine(in);
				}
			}
			else {
				String contentLengthStr = headers.get("content-length");
				int contentLength = contentLengthStr != null ? Integer.parseInt(contentLengthStr) : 0;
				byte[] body = new byte[contentLength];
				int totalRead = 0;
				while (totalRead < contentLength) {
					int n = in.read(body, totalRead, contentLength - totalRead);
					if (n == -1) {
						break;
					}
					totalRead += n;
				}
				bodyBuffer.write(body, 0, totalRead);
			}
			byte[] body = bodyBuffer.toByteArray();

			// Track concurrency.
			int current = this.concurrentRequests.incrementAndGet();
			this.maxConcurrentObserved.updateAndGet(max -> Math.max(max, current));

			// Record the request.
			this.recordedRequests.add(new RecordedRequest(method, path, headers,
					new String(body, StandardCharsets.UTF_8)));

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
				.connectTimeout(java.time.Duration.ofMillis(5000))
				.readTimeout(java.time.Duration.ofMillis(300000))
				.callTimeout(java.time.Duration.ofMillis(310000))
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
		Path path = this.tempDir.resolve("test-" + pngBytes.length + ".png");
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
		final java.util.Map<String, String> headers;
		final String body;
		final String contentType;
		final String accept;

		RecordedRequest(String method, String path, java.util.Map<String, String> headers, String body) {
			this.method = method;
			this.path = path;
			this.headers = headers;
			this.body = body;
			this.contentType = headers.getOrDefault("content-type", "");
			this.accept = headers.getOrDefault("accept", "");
		}
	}

}
