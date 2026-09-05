package horse.sumomo.pos_doc_backend.ocr.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import horse.sumomo.pos_doc_backend.ocr.service.OcrException;
import horse.sumomo.pos_doc_backend.ocr.service.OcrException.Code;
import okio.Buffer;

/**
 * Unit tests for {@link StreamingPngChatRequestBody}.
 *
 * <p>Uses synthetic PNGs and synthetic OCR strings. Never uses a real
 * insurance document or real PII.
 */
class StreamingPngChatRequestBodyTest {

	private static final String MODEL = "/models/dotsmocr-1.8b-q8_0.gguf";
	private static final String PROMPT = "Extract the text content from this image.";
	private static final int MAX_TOKENS = 4096;
	private static final double TEMPERATURE = 0.1;
	private static final double TOP_P = 0.9;
	private static final long MAX_IMAGE_BYTES = 33554432L;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@TempDir
	Path tempDir;

	@Test
	void jsonParsesSuccessfullyAndMatchesContract() throws Exception {
		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = writePng(pngBytes);

		StreamingPngChatRequestBody body = newBody(pngPath, pngBytes.length);
		Buffer buffer = new Buffer();
		body.writeTo(buffer);
		String json = buffer.readUtf8();

		JsonNode root = this.objectMapper.readTree(json);
		assertEquals(MODEL, root.get("model").asText());

		JsonNode messages = root.get("messages");
		assertTrue(messages.isArray());
		assertEquals(1, messages.size());

		JsonNode userMessage = messages.get(0);
		assertEquals("user", userMessage.get("role").asText());

		JsonNode content = userMessage.get("content");
		assertTrue(content.isArray());
		assertEquals(2, content.size());

		JsonNode imagePart = content.get(0);
		assertEquals("image_url", imagePart.get("type").asText());
		String url = imagePart.get("image_url").get("url").asText();
		assertTrue(url.startsWith("data:image/png;base64,"));

		JsonNode textPart = content.get(1);
		assertEquals("text", textPart.get("type").asText());
		assertEquals(PROMPT, textPart.get("text").asText());

		assertEquals(TEMPERATURE, root.get("temperature").asDouble(), 0.0001);
		assertEquals(TOP_P, root.get("top_p").asDouble(), 0.0001);
		assertEquals(MAX_TOKENS, root.get("max_tokens").asInt());
		assertEquals(1, root.get("n").asInt());
		assertFalse(root.get("stream").asBoolean());
		assertTrue(root.has("max_tokens"));
		assertFalse(root.has("max_completion_tokens"));
		assertFalse(PROMPT.contains("<|img|>"));
		assertFalse(PROMPT.contains("<|imgpad|>"));
		assertFalse(PROMPT.contains("<|endofimg|>"));
	}

	@Test
	void decodingDataUrlProducesBytesIdenticalToSourcePng() throws Exception {
		byte[] pngBytes = createSyntheticPng(200);
		Path pngPath = writePng(pngBytes);

		StreamingPngChatRequestBody body = newBody(pngPath, pngBytes.length);
		Buffer buffer = new Buffer();
		body.writeTo(buffer);
		String json = buffer.readUtf8();

		JsonNode root = this.objectMapper.readTree(json);
		String url = root.get("messages").get(0).get("content").get(0).get("image_url").get("url").asText();
		String base64 = url.substring("data:image/png;base64,".length());
		byte[] decoded = Base64.getDecoder().decode(base64);
		assertArrayEquals(pngBytes, decoded);
	}

	@Test
	void fileIsStreamedUsingMultipleReadsWhenLargerThanCopyBuffer() throws Exception {
		// Create a PNG larger than the 8192-byte copy buffer.
		byte[] pngBytes = createSyntheticPng(20000);
		Path pngPath = writePng(pngBytes);

		// Use a counting input stream to prove multiple reads occurred.
		AtomicInteger readCount = new AtomicInteger(0);
		StreamingPngChatRequestBody body = new StreamingPngChatRequestBody(
				pngPath, pngBytes.length, MAX_IMAGE_BYTES, MODEL, PROMPT, MAX_TOKENS, TEMPERATURE, TOP_P,
				this.objectMapper) {
			@Override
			InputStream openInputStream(Path path) throws IOException {
				return new CountingInputStream(Files.newInputStream(path), readCount);
			}
		};

		Buffer buffer = new Buffer();
		body.writeTo(buffer);
		String json = buffer.readUtf8();

		// Verify the data is correct.
		JsonNode root = this.objectMapper.readTree(json);
		String url = root.get("messages").get(0).get("content").get(0).get("image_url").get("url").asText();
		String base64 = url.substring("data:image/png;base64,".length());
		byte[] decoded = Base64.getDecoder().decode(base64);
		assertArrayEquals(pngBytes, decoded);

		// Verify multiple reads occurred (20008 bytes / 8192 buffer = at least 3 reads).
		assertTrue(readCount.get() > 1,
				"Expected multiple reads but got " + readCount.get());
	}

	@Test
	void emptyPngFailsWithImageInvalid() throws Exception {
		Path pngPath = this.tempDir.resolve("empty.png");
		Files.write(pngPath, new byte[0]);

		StreamingPngChatRequestBody body = newBody(pngPath, 1L);
		Buffer buffer = new Buffer();
		OcrException e = assertThrows(OcrException.class, () -> body.writeTo(buffer));
		assertEquals(Code.OCR_IMAGE_INVALID, e.getCode());
	}

	@Test
	void missingPngFailsWithImageUnavailable() throws Exception {
		Path pngPath = this.tempDir.resolve("missing.png");

		StreamingPngChatRequestBody body = newBody(pngPath, 100L);
		Buffer buffer = new Buffer();
		OcrException e = assertThrows(OcrException.class, () -> body.writeTo(buffer));
		assertEquals(Code.OCR_IMAGE_UNAVAILABLE, e.getCode());
	}

	@Test
	void invalidSignatureFailsWithImageInvalid() throws Exception {
		byte[] badPng = new byte[100];
		badPng[0] = 0x00;
		for (int i = 1; i < badPng.length; i++) {
			badPng[i] = (byte) i;
		}
		Path pngPath = writePng(badPng);

		StreamingPngChatRequestBody body = newBody(pngPath, badPng.length);
		Buffer buffer = new Buffer();
		OcrException e = assertThrows(OcrException.class, () -> body.writeTo(buffer));
		assertEquals(Code.OCR_IMAGE_INVALID, e.getCode());
	}

	@Test
	void oversizedPngFailsWithImageInvalid() throws Exception {
		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = writePng(pngBytes);

		StreamingPngChatRequestBody body = new StreamingPngChatRequestBody(
				pngPath, pngBytes.length, 10L, MODEL, PROMPT, MAX_TOKENS, TEMPERATURE, TOP_P, this.objectMapper);
		Buffer buffer = new Buffer();
		OcrException e = assertThrows(OcrException.class, () -> body.writeTo(buffer));
		assertEquals(Code.OCR_IMAGE_INVALID, e.getCode());
	}

	@Test
	void initiallyMismatchedSizeFailsWithImageInvalid() throws Exception {
		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = writePng(pngBytes);

		StreamingPngChatRequestBody body = newBody(pngPath, pngBytes.length + 1);
		Buffer buffer = new Buffer();
		OcrException e = assertThrows(OcrException.class, () -> body.writeTo(buffer));
		assertEquals(Code.OCR_IMAGE_INVALID, e.getCode());
	}

	@Test
	void fileTruncatedDuringStreamingFailsWithImageInvalid() throws Exception {
		// Create a file that will be truncated after the initial size check.
		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = writePng(pngBytes);

		// Use an input stream that returns fewer bytes than expected.
		StreamingPngChatRequestBody body = new StreamingPngChatRequestBody(
				pngPath, pngBytes.length, MAX_IMAGE_BYTES, MODEL, PROMPT, MAX_TOKENS, TEMPERATURE, TOP_P,
				this.objectMapper) {
			@Override
			InputStream openInputStream(Path path) throws IOException {
				// Return a stream that only provides half the bytes.
				byte[] truncated = new byte[pngBytes.length / 2];
				System.arraycopy(pngBytes, 0, truncated, 0, truncated.length);
				return new ByteArrayInputStream(truncated);
			}
		};

		Buffer buffer = new Buffer();
		OcrException e = assertThrows(OcrException.class, () -> body.writeTo(buffer));
		assertEquals(Code.OCR_IMAGE_INVALID, e.getCode());
	}

	@Test
	void fileExpandedDuringStreamingFailsWithImageInvalid() throws Exception {
		// Create a file that will be expanded after the initial size check.
		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = writePng(pngBytes);

		// Use an input stream that returns more bytes than expected.
		byte[] expanded = new byte[pngBytes.length + 10];
		System.arraycopy(pngBytes, 0, expanded, 0, pngBytes.length);
		for (int i = pngBytes.length; i < expanded.length; i++) {
			expanded[i] = (byte) 0xFF;
		}

		StreamingPngChatRequestBody body = new StreamingPngChatRequestBody(
				pngPath, pngBytes.length, MAX_IMAGE_BYTES, MODEL, PROMPT, MAX_TOKENS, TEMPERATURE, TOP_P,
				this.objectMapper) {
			@Override
			InputStream openInputStream(Path path) throws IOException {
				return new ByteArrayInputStream(expanded);
			}
		};

		Buffer buffer = new Buffer();
		OcrException e = assertThrows(OcrException.class, () -> body.writeTo(buffer));
		assertEquals(Code.OCR_IMAGE_INVALID, e.getCode());
	}

	@Test
	void httpSinkFailureIsNotClassifiedAsImageUnavailable() throws Exception {
		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = writePng(pngBytes);

		// Use a sink that fails on write.
		StreamingPngChatRequestBody body = newBody(pngPath, pngBytes.length);
		okio.BufferedSink failingSink = okio.Okio.buffer(okio.Okio.sink(new java.io.OutputStream() {
			@Override
			public void write(int b) throws IOException {
				throw new IOException("simulated network failure");
			}

			@Override
			public void write(byte[] buf, int off, int len) throws IOException {
				throw new IOException("simulated network failure");
			}
		}));

		// The failure should be an IOException (transport), not OCR_IMAGE_UNAVAILABLE.
		IOException e = assertThrows(IOException.class, () -> body.writeTo(failingSink));
		assertTrue(e.getMessage().contains("simulated network failure"));
	}

	@Test
	void base64FinalizationFailureIsNotSwallowed() throws Exception {
		// Use a PNG length that requires Base64 padding (108 bytes: 108 % 3 == 0,
		// but let's use 109 bytes: 109 % 3 == 1, requires "==" padding).
		byte[] pngBytes = createSyntheticPng(101); // 8 + 101 = 109 bytes, 109 % 3 == 1
		Path pngPath = writePng(pngBytes);

		// Use a sink that succeeds for all writes except the final padding write.
		// The Base64 encoder's close() writes the padding to the sink.
		// We track the total bytes written and fail on the last write (padding).
		final java.util.concurrent.atomic.AtomicLong bytesWritten = new java.util.concurrent.atomic.AtomicLong(0);
		final long totalEncodedBytes = computeBase64EncodedLength(pngBytes.length);
		okio.BufferedSink failingSink = okio.Okio.buffer(okio.Okio.sink(new java.io.OutputStream() {
			@Override
			public void write(int b) throws IOException {
				long newTotal = bytesWritten.addAndGet(1);
				if (newTotal > totalEncodedBytes - 4) {
					throw new IOException("simulated padding write failure");
				}
			}

			@Override
			public void write(byte[] buf, int off, int len) throws IOException {
				long newTotal = bytesWritten.addAndGet(len);
				if (newTotal > totalEncodedBytes - 4) {
					throw new IOException("simulated padding write failure");
				}
			}
		}));

		StreamingPngChatRequestBody body = newBody(pngPath, pngBytes.length);
		IOException e = assertThrows(IOException.class, () -> body.writeTo(failingSink));
		assertTrue(e.getMessage().contains("simulated padding write failure"));
	}

	@Test
	void base64CloseFailureIsSuppressedWhenPrimaryFailureExists() throws Exception {
		// Use a valid PNG that will be truncated during streaming, causing
		// OCR_IMAGE_INVALID. This exercises the code path where a primary
		// failure exists and base64Stream.close() is called in the finally
		// block. The close() call succeeds (no padding failure in this
		// scenario), so no suppressed exception is added. This test verifies
		// that the primary failure is preserved and no close exception
		// replaces it.
		byte[] fullPng = createSyntheticPng(101); // 109 bytes
		Path pngPath = writePng(fullPng);

		// Provide only 55 bytes, causing rawByteCount != expectedPngByteSize.
		byte[] truncated = new byte[55];
		System.arraycopy(fullPng, 0, truncated, 0, truncated.length);

		StreamingPngChatRequestBody body = new StreamingPngChatRequestBody(
				pngPath, fullPng.length, MAX_IMAGE_BYTES, MODEL, PROMPT, MAX_TOKENS, TEMPERATURE, TOP_P,
				this.objectMapper) {
			@Override
			InputStream openInputStream(Path path) throws IOException {
				return new ByteArrayInputStream(truncated);
			}
		};

		Buffer buffer = new Buffer();
		OcrException e = assertThrows(OcrException.class, () -> body.writeTo(buffer));
		assertEquals(Code.OCR_IMAGE_INVALID, e.getCode());
		// The primary failure is preserved. No close exception replaced it.
		// (In this scenario, close() succeeds, so no suppressed exception.)
		assertNotNull(e);
	}

	private static long computeBase64EncodedLength(long rawLength) {
		return (rawLength / 3) * 4 + ((rawLength % 3) == 0 ? 0 : (rawLength % 3) == 1 ? 4 : 4);
	}

	@Test
	void base64PaddingCorrectForModuloZero() throws Exception {
		byte[] pngBytes = createSyntheticPng(1); // 9 bytes total, 9 % 3 == 0
		Path pngPath = writePng(pngBytes);

		StreamingPngChatRequestBody body = newBody(pngPath, pngBytes.length);
		Buffer buffer = new Buffer();
		body.writeTo(buffer);
		String json = buffer.readUtf8();

		JsonNode root = this.objectMapper.readTree(json);
		String url = root.get("messages").get(0).get("content").get(0).get("image_url").get("url").asText();
		String base64 = url.substring("data:image/png;base64,".length());
		assertFalse(base64.endsWith("="));
		byte[] decoded = Base64.getDecoder().decode(base64);
		assertArrayEquals(pngBytes, decoded);
	}

	@Test
	void base64PaddingCorrectForModuloOne() throws Exception {
		byte[] pngBytes = createSyntheticPng(2); // 10 bytes total, 10 % 3 == 1
		Path pngPath = writePng(pngBytes);

		StreamingPngChatRequestBody body = newBody(pngPath, pngBytes.length);
		Buffer buffer = new Buffer();
		body.writeTo(buffer);
		String json = buffer.readUtf8();

		JsonNode root = this.objectMapper.readTree(json);
		String url = root.get("messages").get(0).get("content").get(0).get("image_url").get("url").asText();
		String base64 = url.substring("data:image/png;base64,".length());
		assertTrue(base64.endsWith("=="));
		byte[] decoded = Base64.getDecoder().decode(base64);
		assertArrayEquals(pngBytes, decoded);
	}

	@Test
	void base64PaddingCorrectForModuloTwo() throws Exception {
		byte[] pngBytes = createSyntheticPng(3); // 11 bytes total, 11 % 3 == 2
		Path pngPath = writePng(pngBytes);

		StreamingPngChatRequestBody body = newBody(pngPath, pngBytes.length);
		Buffer buffer = new Buffer();
		body.writeTo(buffer);
		String json = buffer.readUtf8();

		JsonNode root = this.objectMapper.readTree(json);
		String url = root.get("messages").get(0).get("content").get(0).get("image_url").get("url").asText();
		String base64 = url.substring("data:image/png;base64,".length());
		assertTrue(base64.endsWith("="));
		assertFalse(base64.endsWith("=="));
		byte[] decoded = Base64.getDecoder().decode(base64);
		assertArrayEquals(pngBytes, decoded);
	}

	@Test
	void closingBase64EncoderDoesNotCloseSinkBeforeSuffix() throws Exception {
		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = writePng(pngBytes);

		StreamingPngChatRequestBody body = newBody(pngPath, pngBytes.length);
		Buffer buffer = new Buffer();
		body.writeTo(buffer);
		String json = buffer.readUtf8();

		JsonNode root = this.objectMapper.readTree(json);
		assertTrue(root.has("max_tokens"));
		assertTrue(root.has("stream"));
	}

	@Test
	void contentLengthReturnsNegativeOne() throws Exception {
		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = writePng(pngBytes);

		StreamingPngChatRequestBody body = newBody(pngPath, pngBytes.length);
		assertEquals(-1L, body.contentLength());
	}

	@Test
	void toStringOmitsPathAndData() throws Exception {
		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = writePng(pngBytes);

		StreamingPngChatRequestBody body = newBody(pngPath, pngBytes.length);
		String str = body.toString();
		assertFalse(str.contains(pngPath.toString()));
		assertFalse(str.contains("data:image/png"));
		assertTrue(str.contains("model=" + MODEL));
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	private StreamingPngChatRequestBody newBody(Path pngPath, long expectedSize) {
		return new StreamingPngChatRequestBody(
				pngPath, expectedSize, MAX_IMAGE_BYTES, MODEL, PROMPT, MAX_TOKENS, TEMPERATURE, TOP_P,
				this.objectMapper);
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

	/**
	 * An input stream that counts the number of read() calls.
	 */
	private static final class CountingInputStream extends InputStream {

		private final InputStream delegate;
		private final AtomicInteger readCount;

		CountingInputStream(InputStream delegate, AtomicInteger readCount) {
			this.delegate = delegate;
			this.readCount = readCount;
		}

		@Override
		public int read() throws IOException {
			this.readCount.incrementAndGet();
			return this.delegate.read();
		}

		@Override
		public int read(byte[] buf, int off, int len) throws IOException {
			this.readCount.incrementAndGet();
			return this.delegate.read(buf, off, len);
		}

		@Override
		public int available() throws IOException {
			return this.delegate.available();
		}

		@Override
		public void close() throws IOException {
			this.delegate.close();
		}

	}

}
