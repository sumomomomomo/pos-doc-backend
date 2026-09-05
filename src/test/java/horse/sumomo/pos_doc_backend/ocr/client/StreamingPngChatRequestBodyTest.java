package horse.sumomo.pos_doc_backend.ocr.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

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

		StreamingPngChatRequestBody body = new StreamingPngChatRequestBody(
				pngPath, pngBytes.length, MAX_IMAGE_BYTES, MODEL, PROMPT, MAX_TOKENS, TEMPERATURE, TOP_P,
				this.objectMapper);

		Buffer buffer = new Buffer();
		body.writeTo(buffer);
		String json = buffer.readUtf8();

		JsonNode root = this.objectMapper.readTree(json);

		// Model
		assertEquals(MODEL, root.get("model").asText());

		// Messages
		JsonNode messages = root.get("messages");
		assertTrue(messages.isArray());
		assertEquals(1, messages.size());

		JsonNode userMessage = messages.get(0);
		assertEquals("user", userMessage.get("role").asText());

		JsonNode content = userMessage.get("content");
		assertTrue(content.isArray());
		assertEquals(2, content.size());

		// Content part 0: image_url
		JsonNode imagePart = content.get(0);
		assertEquals("image_url", imagePart.get("type").asText());
		JsonNode imageUrl = imagePart.get("image_url");
		String url = imageUrl.get("url").asText();
		assertTrue(url.startsWith("data:image/png;base64,"));

		// Content part 1: text
		JsonNode textPart = content.get(1);
		assertEquals("text", textPart.get("type").asText());
		assertEquals(PROMPT, textPart.get("text").asText());

		// Generation parameters
		assertEquals(TEMPERATURE, root.get("temperature").asDouble(), 0.0001);
		assertEquals(TOP_P, root.get("top_p").asDouble(), 0.0001);
		assertEquals(MAX_TOKENS, root.get("max_tokens").asInt());
		assertEquals(1, root.get("n").asInt());
		assertFalse(root.get("stream").asBoolean());

		// max_tokens exists, max_completion_tokens does not
		assertTrue(root.has("max_tokens"));
		assertFalse(root.has("max_completion_tokens"));

		// No special image-control token in the prompt
		assertFalse(PROMPT.contains("<|img|>"));
		assertFalse(PROMPT.contains("<|imgpad|>"));
		assertFalse(PROMPT.contains("<|endofimg|>"));
	}

	@Test
	void decodingDataUrlProducesBytesIdenticalToSourcePng() throws Exception {
		byte[] pngBytes = createSyntheticPng(200);
		Path pngPath = writePng(pngBytes);

		StreamingPngChatRequestBody body = new StreamingPngChatRequestBody(
				pngPath, pngBytes.length, MAX_IMAGE_BYTES, MODEL, PROMPT, MAX_TOKENS, TEMPERATURE, TOP_P,
				this.objectMapper);

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

		StreamingPngChatRequestBody body = new StreamingPngChatRequestBody(
				pngPath, pngBytes.length, MAX_IMAGE_BYTES, MODEL, PROMPT, MAX_TOKENS, TEMPERATURE, TOP_P,
				this.objectMapper);

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
	void emptyPngFailsWithImageInvalid() throws Exception {
		// Write a 1-byte file (not a valid PNG) and expect the signature check to fail.
		Path pngPath = this.tempDir.resolve("empty.png");
		Files.write(pngPath, new byte[] { 0x00 });

		StreamingPngChatRequestBody body = new StreamingPngChatRequestBody(
				pngPath, 1L, MAX_IMAGE_BYTES, MODEL, PROMPT, MAX_TOKENS, TEMPERATURE, TOP_P, this.objectMapper);

		Buffer buffer = new Buffer();
		OcrException e = assertThrows(OcrException.class, () -> body.writeTo(buffer));
		assertEquals(Code.OCR_IMAGE_INVALID, e.getCode());
	}

	@Test
	void missingPngFailsWithImageUnavailable() throws Exception {
		Path pngPath = this.tempDir.resolve("missing.png");

		StreamingPngChatRequestBody body = new StreamingPngChatRequestBody(
				pngPath, 100L, MAX_IMAGE_BYTES, MODEL, PROMPT, MAX_TOKENS, TEMPERATURE, TOP_P, this.objectMapper);

		Buffer buffer = new Buffer();
		OcrException e = assertThrows(OcrException.class, () -> body.writeTo(buffer));
		assertEquals(Code.OCR_IMAGE_UNAVAILABLE, e.getCode());
	}

	@Test
	void invalidSignatureFailsWithImageInvalid() throws Exception {
		// Create a file with wrong PNG signature.
		byte[] badPng = new byte[100];
		badPng[0] = 0x00; // Wrong first byte
		for (int i = 1; i < badPng.length; i++) {
			badPng[i] = (byte) i;
		}
		Path pngPath = writePng(badPng);

		StreamingPngChatRequestBody body = new StreamingPngChatRequestBody(
				pngPath, badPng.length, MAX_IMAGE_BYTES, MODEL, PROMPT, MAX_TOKENS, TEMPERATURE, TOP_P,
				this.objectMapper);

		Buffer buffer = new Buffer();
		OcrException e = assertThrows(OcrException.class, () -> body.writeTo(buffer));
		assertEquals(Code.OCR_IMAGE_INVALID, e.getCode());
	}

	@Test
	void oversizedPngFailsWithImageInvalid() throws Exception {
		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = writePng(pngBytes);

		// Set maxImageBytes smaller than the actual size.
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

		// Expected size differs from actual.
		StreamingPngChatRequestBody body = new StreamingPngChatRequestBody(
				pngPath, pngBytes.length + 1, MAX_IMAGE_BYTES, MODEL, PROMPT, MAX_TOKENS, TEMPERATURE, TOP_P,
				this.objectMapper);

		Buffer buffer = new Buffer();
		OcrException e = assertThrows(OcrException.class, () -> body.writeTo(buffer));
		assertEquals(Code.OCR_IMAGE_INVALID, e.getCode());
	}

	@Test
	void base64PaddingCorrectForModuloZero() throws Exception {
		// Create a PNG whose total length (8 signature + payload) is a multiple of 3.
		byte[] pngBytes = createSyntheticPng(1); // 9 bytes total, 9 % 3 == 0
		Path pngPath = writePng(pngBytes);

		StreamingPngChatRequestBody body = new StreamingPngChatRequestBody(
				pngPath, pngBytes.length, MAX_IMAGE_BYTES, MODEL, PROMPT, MAX_TOKENS, TEMPERATURE, TOP_P,
				this.objectMapper);

		Buffer buffer = new Buffer();
		body.writeTo(buffer);
		String json = buffer.readUtf8();

		JsonNode root = this.objectMapper.readTree(json);
		String url = root.get("messages").get(0).get("content").get(0).get("image_url").get("url").asText();
		String base64 = url.substring("data:image/png;base64,".length());
		// No padding needed for modulo 0.
		assertFalse(base64.endsWith("="));
		byte[] decoded = Base64.getDecoder().decode(base64);
		assertArrayEquals(pngBytes, decoded);
	}

	@Test
	void base64PaddingCorrectForModuloOne() throws Exception {
		// Create a PNG whose total length (8 signature + payload) is 1 mod 3.
		byte[] pngBytes = createSyntheticPng(2); // 10 bytes total, 10 % 3 == 1
		Path pngPath = writePng(pngBytes);

		StreamingPngChatRequestBody body = new StreamingPngChatRequestBody(
				pngPath, pngBytes.length, MAX_IMAGE_BYTES, MODEL, PROMPT, MAX_TOKENS, TEMPERATURE, TOP_P,
				this.objectMapper);

		Buffer buffer = new Buffer();
		body.writeTo(buffer);
		String json = buffer.readUtf8();

		JsonNode root = this.objectMapper.readTree(json);
		String url = root.get("messages").get(0).get("content").get(0).get("image_url").get("url").asText();
		String base64 = url.substring("data:image/png;base64,".length());
		// Two '=' padding for modulo 1.
		assertTrue(base64.endsWith("=="));
		byte[] decoded = Base64.getDecoder().decode(base64);
		assertArrayEquals(pngBytes, decoded);
	}

	@Test
	void base64PaddingCorrectForModuloTwo() throws Exception {
		// Create a PNG whose total length (8 signature + payload) is 2 mod 3.
		byte[] pngBytes = createSyntheticPng(3); // 11 bytes total, 11 % 3 == 2
		Path pngPath = writePng(pngBytes);

		StreamingPngChatRequestBody body = new StreamingPngChatRequestBody(
				pngPath, pngBytes.length, MAX_IMAGE_BYTES, MODEL, PROMPT, MAX_TOKENS, TEMPERATURE, TOP_P,
				this.objectMapper);

		Buffer buffer = new Buffer();
		body.writeTo(buffer);
		String json = buffer.readUtf8();

		JsonNode root = this.objectMapper.readTree(json);
		String url = root.get("messages").get(0).get("content").get(0).get("image_url").get("url").asText();
		String base64 = url.substring("data:image/png;base64,".length());
		// One '=' padding for modulo 2.
		assertTrue(base64.endsWith("="));
		assertFalse(base64.endsWith("=="));
		byte[] decoded = Base64.getDecoder().decode(base64);
		assertArrayEquals(pngBytes, decoded);
	}

	@Test
	void closingBase64EncoderDoesNotCloseSinkBeforeSuffix() throws Exception {
		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = writePng(pngBytes);

		StreamingPngChatRequestBody body = new StreamingPngChatRequestBody(
				pngPath, pngBytes.length, MAX_IMAGE_BYTES, MODEL, PROMPT, MAX_TOKENS, TEMPERATURE, TOP_P,
				this.objectMapper);

		Buffer buffer = new Buffer();
		body.writeTo(buffer);
		String json = buffer.readUtf8();

		// The JSON must be complete and parseable, proving the suffix was
		// written after the base64 encoder was closed.
		JsonNode root = this.objectMapper.readTree(json);
		assertTrue(root.has("max_tokens"));
		assertTrue(root.has("stream"));
	}

	@Test
	void contentLengthReturnsNegativeOne() throws Exception {
		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = writePng(pngBytes);

		StreamingPngChatRequestBody body = new StreamingPngChatRequestBody(
				pngPath, pngBytes.length, MAX_IMAGE_BYTES, MODEL, PROMPT, MAX_TOKENS, TEMPERATURE, TOP_P,
				this.objectMapper);

		assertEquals(-1L, body.contentLength());
	}

	@Test
	void toStringOmitsPathAndData() throws Exception {
		byte[] pngBytes = createSyntheticPng(100);
		Path pngPath = writePng(pngBytes);

		StreamingPngChatRequestBody body = new StreamingPngChatRequestBody(
				pngPath, pngBytes.length, MAX_IMAGE_BYTES, MODEL, PROMPT, MAX_TOKENS, TEMPERATURE, TOP_P,
				this.objectMapper);

		String str = body.toString();
		assertFalse(str.contains(pngPath.toString()));
		assertFalse(str.contains("data:image/png"));
		assertTrue(str.contains("model=" + MODEL));
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	/**
	 * Creates a synthetic PNG with the correct 8-byte signature followed by
	 * {@code payloadLength} bytes of deterministic filler.
	 */
	private static byte[] createSyntheticPng(int payloadLength) {
		byte[] png = new byte[8 + payloadLength];
		// PNG signature
		png[0] = (byte) 0x89;
		png[1] = 0x50;
		png[2] = 0x4E;
		png[3] = 0x47;
		png[4] = 0x0D;
		png[5] = 0x0A;
		png[6] = 0x1A;
		png[7] = 0x0A;
		// Deterministic filler
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

}
