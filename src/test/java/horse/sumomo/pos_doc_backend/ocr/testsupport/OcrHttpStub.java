package horse.sumomo.pos_doc_backend.ocr.testsupport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * calls and inspect request details.
 */
public final class OcrHttpStub implements AutoCloseable {

	private static final String MODEL = "/models/dotsmocr-1.8b-q8_0.gguf";
	private static final String SYNTHETIC_OCR_TEXT = "SYNTHETIC OCR TEXT";
	private static final long MAX_CAPTURE_BYTES = 10 * 1024 * 1024;

	private final int port;
	private final Thread serverThread;
	private volatile ServerSocket serverSocket;
	private volatile boolean running;
	private final List<RecordedRequest> requests = new ArrayList<>();
	private final AtomicInteger requestCount = new AtomicInteger(0);

	/** Configurable response for the next request. */
	private volatile String nextResponseJson;
	private volatile int nextResponseStatus;
	private volatile String nextResponseContentType;

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
	 * Sets the response for the next request. Subsequent requests use the
	 * same response until changed again.
	 */
	public void setNextResponse(String ocrText, int status, String contentType) {
		this.nextResponseJson = buildResponse(ocrText);
		this.nextResponseStatus = status;
		this.nextResponseContentType = contentType;
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
			String path = parts[1];

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

			// Read body.
			String contentLengthStr = headers.get("content-length");
			long contentLength = contentLengthStr != null ? Long.parseLong(contentLengthStr) : 0;
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
					break;
				}
			}
			byte[] body = bodyBuffer.toByteArray();

			this.requestCount.incrementAndGet();
			this.requests.add(new RecordedRequest(method, path, headers,
					new String(body, StandardCharsets.UTF_8)));

			String responseBody = this.nextResponseJson;
			byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
			String responseHeaders = "HTTP/1.1 " + this.nextResponseStatus + " "
					+ reasonPhrase(this.nextResponseStatus) + "\r\n"
					+ "Content-Type: " + this.nextResponseContentType + "\r\n"
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

}
