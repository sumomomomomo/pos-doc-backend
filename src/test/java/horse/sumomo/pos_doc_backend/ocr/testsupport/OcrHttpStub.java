package horse.sumomo.pos_doc_backend.ocr.testsupport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only ephemeral loopback HTTP server that stubs the llama.cpp
 * {@code POST /v1/chat/completions} endpoint.
 *
 * <p>Consistent with Task 8's protocol tests: bound to {@code 127.0.0.1}
 * on an ephemeral port. Returns the exact production model ID and valid
 * deterministic synthetic OCR text. No real insurance documents or real
 * PII. No automated test contacts {@code 192.168.1.34}.
 *
 * <p>The stub records each request so tests can assert the number of OCR
 * calls and inspect request details. Request capture is thread-safe.
 *
 * <p>Supports both {@code Content-Length} and chunked transfer encoding
 * for request bodies, since OkHttp sends chunked encoding when the
 * {@code RequestBody.contentLength()} returns -1.
 *
 * <p>Supports a deterministic queued-response mechanism: tests can enqueue
 * a sequence of responses (e.g., 503 then 200) and the stub will return
 * them in order, one per request. When the queue is exhausted, the stub
 * falls back to the default response.
 */
public final class OcrHttpStub implements AutoCloseable {

	private static final String MODEL = "/models/dotsmocr-1.8b-q8_0.gguf";
	private static final String SYNTHETIC_OCR_TEXT = "SYNTHETIC OCR TEXT";
	private static final long MAX_CAPTURE_BYTES = 10 * 1024 * 1024;

	private final int port;
	private final Thread serverThread;
	private volatile ServerSocket serverSocket;
	private volatile boolean running;
	private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
	private final AtomicInteger requestCount = new AtomicInteger(0);

	/** Default response used when the queue is empty. */
	private volatile String nextResponseJson;
	private volatile int nextResponseStatus;
	private volatile String nextResponseContentType;

	/**
	 * Queue of deterministic responses. Each entry is consumed by the next
	 * request. When the queue is empty, the default response is used.
	 */
	private final ConcurrentLinkedQueue<QueuedResponse> responseQueue = new ConcurrentLinkedQueue<>();

	public OcrHttpStub() throws IOException {
		this("SYNTHETIC OCR TEXT", 200, "application/json");
	}

	public OcrHttpStub(String ocrText, int status, String contentType) throws IOException {
		try (ServerSocket probe = new ServerSocket(0)) {
			this.port = probe.getLocalPort();
		}
		this.nextResponseJson = buildResponse(ocrText);
		this.nextResponseStatus = status;
		this.nextResponseContentType = contentType;
		this.running = true;
		this.serverSocket = new ServerSocket();
		this.serverSocket.bind(new InetSocketAddress("127.0.0.1", this.port));
		this.serverSocket.setSoTimeout(15000);
		this.serverThread = new Thread(this::acceptLoop, "ocr-stub-server");
		this.serverThread.setDaemon(true);
		this.serverThread.start();
	}

	public int getPort() {
		return this.port;
	}

	public String getServerOrigin() {
		return "http://127.0.0.1:" + this.port;
	}

	public int getRequestCount() {
		return this.requestCount.get();
	}

	public List<RecordedRequest> getRequests() {
		return List.copyOf(this.requests);
	}

	/**
	 * Sets the default response for requests when the queue is empty.
	 */
	public void setNextResponse(String ocrText, int status, String contentType) {
		this.nextResponseJson = buildResponse(ocrText);
		this.nextResponseStatus = status;
		this.nextResponseContentType = contentType;
	}

	/**
	 * Enqueues a deterministic response for the next request. Responses
	 * are consumed in FIFO order. When the queue is exhausted, the default
	 * response is used.
	 */
	public void enqueueResponse(String ocrText, int status, String contentType) {
		this.responseQueue.add(new QueuedResponse(buildResponse(ocrText), status, contentType));
	}

	/**
	 * Enqueues a raw JSON response for the next request.
	 */
	public void enqueueRawResponse(String json, int status, String contentType) {
		this.responseQueue.add(new QueuedResponse(json, status, contentType));
	}

	/**
	 * Clears all queued responses and resets the default response to the
	 * original successful synthetic response. Does not stop or recreate
	 * the HTTP server. Thread-safe.
	 */
	public synchronized void resetResponses() {
		this.responseQueue.clear();
		this.nextResponseJson = buildResponse(SYNTHETIC_OCR_TEXT);
		this.nextResponseStatus = 200;
		this.nextResponseContentType = "application/json";
	}

	@Override
	public void close() throws IOException {
		this.running = false;
		if (this.serverSocket != null) {
			this.serverSocket.close();
		}
		if (this.serverThread != null) {
			try {
				this.serverThread.join(5000);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	private void acceptLoop() {
		while (this.running) {
			try {
				Socket client = this.serverSocket.accept();
				Thread t = new Thread(() -> handleClient(client));
				t.setDaemon(true);
				t.start();
			}
			catch (IOException e) {
				if (this.running) {
					// Timeout or other I/O error; continue.
				}
			}
		}
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
			String path = parts.length > 1 ? parts[1] : "/";

			Map<String, String> headers = new LinkedHashMap<>();
			String headerLine;
			while ((headerLine = readLine(in)) != null && !headerLine.isEmpty()) {
				int colon = headerLine.indexOf(':');
				if (colon > 0) {
					String name = headerLine.substring(0, colon).trim();
					String value = headerLine.substring(colon + 1).trim();
					headers.put(name.toLowerCase(), value);
				}
			}

			// Read body: support both Content-Length and chunked encoding.
			byte[] body;
			String transferEncoding = headers.get("transfer-encoding");
			if (transferEncoding != null && transferEncoding.toLowerCase().contains("chunked")) {
				body = readChunkedBody(in);
			}
			else {
				String contentLengthStr = headers.get("content-length");
				long contentLength = contentLengthStr != null ? Long.parseLong(contentLengthStr) : 0;
				body = readFixedLengthBody(in, contentLength);
			}

			this.requestCount.incrementAndGet();
			this.requests.add(new RecordedRequest(method, path, headers,
					new String(body, StandardCharsets.UTF_8)));

			// Select response: from queue if available, else default.
			QueuedResponse queued = this.responseQueue.poll();
			String responseBody;
			int responseStatus;
			String responseContentType;
			if (queued != null) {
				responseBody = queued.json();
				responseStatus = queued.status();
				responseContentType = queued.contentType();
			}
			else {
				responseBody = this.nextResponseJson;
				responseStatus = this.nextResponseStatus;
				responseContentType = this.nextResponseContentType;
			}

			byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
			String responseHeaders = "HTTP/1.1 " + responseStatus + " "
					+ reasonPhrase(responseStatus) + "\r\n"
					+ "Content-Type: " + responseContentType + "\r\n"
					+ "Content-Length: " + responseBytes.length + "\r\n"
					+ "Connection: close\r\n"
					+ "\r\n";
			out.write(responseHeaders.getBytes(StandardCharsets.UTF_8));
			out.write(responseBytes);
			out.flush();
		}
		catch (IOException e) {
			// Connection closed or other I/O error; ignore.
		}
	}

	/**
	 * Reads a fixed-length body from the input stream, bounded by
	 * {@link #MAX_CAPTURE_BYTES}.
	 */
	private static byte[] readFixedLengthBody(InputStream in, long contentLength) throws IOException {
		ByteArrayOutputStream bodyBuffer = new ByteArrayOutputStream();
		byte[] copyBuf = new byte[8192];
		long remaining = contentLength;
		while (remaining > 0) {
			int toRead = (int) Math.min(remaining, copyBuf.length);
			int n = in.read(copyBuf, 0, toRead);
			if (n == -1) {
				break;
			}
			bodyBuffer.write(copyBuf, 0, n);
			remaining -= n;
			if (bodyBuffer.size() > MAX_CAPTURE_BYTES) {
				// Drain remaining bytes without capturing.
				while (remaining > 0) {
					int drainRead = (int) Math.min(remaining, copyBuf.length);
					int drainN = in.read(copyBuf, 0, drainRead);
					if (drainN == -1) {
						break;
					}
					remaining -= drainN;
				}
				break;
			}
		}
		return bodyBuffer.toByteArray();
	}

	/**
	 * Reads a chunked-transfer-encoded body from the input stream, bounded
	 * by {@link #MAX_CAPTURE_BYTES}. Each chunk is preceded by a
	 * hex-size line and followed by CRLF. The final chunk has size 0.
	 */
	private static byte[] readChunkedBody(InputStream in) throws IOException {
		ByteArrayOutputStream bodyBuffer = new ByteArrayOutputStream();
		byte[] copyBuf = new byte[8192];
		boolean exceededLimit = false;

		while (true) {
			String sizeLine = readLine(in);
			if (sizeLine == null || sizeLine.isEmpty()) {
				break;
			}
			// Strip chunk extensions (e.g., ";ext=value").
			int semicolon = sizeLine.indexOf(';');
			String sizeHex = semicolon >= 0 ? sizeLine.substring(0, semicolon) : sizeLine;
			long chunkSize;
			try {
				chunkSize = Long.parseLong(sizeHex.trim(), 16);
			}
			catch (NumberFormatException e) {
				break;
			}
			if (chunkSize == 0) {
				// Final chunk: read trailing CRLF.
				readLine(in);
				break;
			}

			long remaining = chunkSize;
			while (remaining > 0) {
				int toRead = (int) Math.min(remaining, copyBuf.length);
				int n = in.read(copyBuf, 0, toRead);
				if (n == -1) {
					return bodyBuffer.toByteArray();
				}
				if (!exceededLimit) {
					bodyBuffer.write(copyBuf, 0, n);
					if (bodyBuffer.size() > MAX_CAPTURE_BYTES) {
						exceededLimit = true;
						bodyBuffer.reset();
					}
				}
				remaining -= n;
			}
			// Read trailing CRLF after each chunk.
			readLine(in);
		}
		return bodyBuffer.toByteArray();
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
			case 400 -> "Bad Request";
			case 401 -> "Unauthorized";
			case 403 -> "Forbidden";
			case 404 -> "Not Found";
			case 408 -> "Request Timeout";
			case 429 -> "Too Many Requests";
			case 500 -> "Internal Server Error";
			case 503 -> "Service Unavailable";
			default -> "Unknown";
		};
	}

	private static String buildResponse(String text) {
		return "{\"model\":\"" + MODEL + "\","
				+ "\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + text
				+ "\"},\"finish_reason\":\"stop\"}]}";
	}

	/**
	 * A recorded HTTP request to the stub.
	 */
	public record RecordedRequest(String method, String path, Map<String, String> headers, String body) {
	}

	/**
	 * A queued deterministic response.
	 */
	private record QueuedResponse(String json, int status, String contentType) {
	}

}
