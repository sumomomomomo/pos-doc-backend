package horse.sumomo.pos_doc_backend.controller;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import horse.sumomo.pos_doc_backend.ingestion.application.PosArchiveIntakeService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real HTTP integration test (Task 4-5, step 15 + corrective finding #2 of
 * the latest review).
 *
 * <p>Boots the full Spring context on a random port, then sends an actual
 * multipart HTTP request containing 10 MiB + 1 byte to the embedded servlet
 * container (Tomcat). The container's multipart parser raises
 * {@code MaxUploadSizeExceededException} before the request reaches
 * {@code BoundedUploadSpooler}; the {@link ApiExceptionHandler} advice maps
 * that to {@code 413 ARCHIVE_TOO_LARGE}. The intake service is mocked and
 * must never be invoked.
 *
 * <p>Unlike a MockMvc test (which builds an already-parsed
 * {@code MockMultipartFile} and therefore cannot exercise the servlet
 * container's multipart parser), this test sends raw bytes over a real TCP
 * socket and proves the end-to-end servlet-level rejection path.
 *
 * <p>The outbox relay is disabled and a temporary SQLite database is used so
 * the context boots without a reachable MinIO or RabbitMQ; neither is
 * contacted during this test.
 */
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = {
				"app.messaging.outbox.enabled=false"
		})
@DirtiesContext
class UploadSizeRealHttpRejectionTest {

	private static final int OVERSIZE_BYTES = 10 * 1024 * 1024 + 1;

	@LocalServerPort
	private int port;

	@MockitoBean
	private PosArchiveIntakeService intakeService;

	@DynamicPropertySource
	static void sqliteUrl(DynamicPropertyRegistry registry) throws Exception {
		Path dbFile = Files.createTempFile("pos-doc-real-http-oversize-test", ".db");
		dbFile.toFile().deleteOnExit();
		Path.of(dbFile.toString() + "-wal").toFile().deleteOnExit();
		Path.of(dbFile.toString() + "-shm").toFile().deleteOnExit();
		registry.add("SQLITE_URL", () -> "jdbc:sqlite:" + dbFile);
	}

	@Test
	void realHttpOversizeMultipartIsRejectedWith413ArchiveTooLarge() throws Exception {
		// The intake service must never be invoked for a servlet-rejected
		// upload; it is only present to satisfy the controller's
		// dependency-injection graph.
		org.mockito.Mockito.verifyNoInteractions(this.intakeService);

		URI uri = URI.create("http://localhost:" + this.port + "/api/v1/pos-records");
		URL url = uri.toURL();
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setRequestMethod("POST");
		conn.setDoOutput(true);
		conn.setRequestProperty("Accept", "application/problem+json");

		String filename = "EREF-REAL-HTTP-OVERSIZE-001.zip";
		String boundary = "----test-boundary-9c8f";
		conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

		String prefix = "--" + boundary + "\r\n"
				+ "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
				+ "Content-Type: application/zip\r\n\r\n";
		String suffix = "\r\n--" + boundary + "--\r\n";
		byte[] prefixBytes = prefix.getBytes(StandardCharsets.UTF_8);
		byte[] suffixBytes = suffix.getBytes(StandardCharsets.UTF_8);
		long contentLength = (long) prefixBytes.length + OVERSIZE_BYTES + suffixBytes.length;
		conn.setFixedLengthStreamingMode(contentLength);

		try (OutputStream out = conn.getOutputStream()) {
			out.write(prefixBytes);
			byte[] chunk = new byte[64 * 1024];
			int remaining = OVERSIZE_BYTES;
			while (remaining > 0) {
				int n = Math.min(chunk.length, remaining);
				out.write(chunk, 0, n);
				remaining -= n;
			}
			out.write(suffixBytes);
			out.flush();
		}

		int status = conn.getResponseCode();
		InputStream bodyStream = (status >= 400) ? conn.getErrorStream() : conn.getInputStream();
		String body = "";
		if (bodyStream != null) {
			try (InputStream in = bodyStream) {
				body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			}
		}
		String contentType = conn.getContentType();

		// The required contract: 413 ARCHIVE_TOO_LARGE application/problem+json.
		assertEquals(413, status,
				"server must return 413 ARCHIVE_TOO_LARGE for an oversized multipart upload, got "
						+ status + " with body: " + body);
		assertNotNull(contentType, "413 response must have a Content-Type");
		assertTrue(contentType.contains("application/problem+json"),
				"413 response must be application/problem+json, got: " + contentType);
		assertTrue(body.contains("ARCHIVE_TOO_LARGE"),
				"413 body must contain ARCHIVE_TOO_LARGE: " + body);
		assertTrue(body.contains("\"status\":413"),
				"413 body must contain status=413: " + body);
		assertFalse(body.contains(filename),
				"body must not echo the submitted filename: " + body);
		assertFalse(body.contains("MaxUploadSize"),
				"body must not contain raw exception class name: " + body);

		// Intake service must not have been invoked.
		org.mockito.Mockito.verifyNoInteractions(this.intakeService);
		conn.disconnect();
	}
}
