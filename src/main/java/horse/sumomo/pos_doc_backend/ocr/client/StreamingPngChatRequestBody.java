package horse.sumomo.pos_doc_backend.ocr.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import com.fasterxml.jackson.databind.ObjectMapper;

import horse.sumomo.pos_doc_backend.ocr.service.OcrException;
import horse.sumomo.pos_doc_backend.ocr.service.OcrException.Code;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okio.BufferedSink;

/**
 * OkHttp {@link RequestBody} that serializes the fixed JSON chat-completions
 * request while streaming the PNG as base64.
 *
 * <p>The PNG is never read into memory as a whole. It is streamed through
 * {@link Base64#getEncoder()} with an 8192-byte buffer. The JSON prefix and
 * suffix are small and held as UTF-8 byte arrays.
 *
 * <p>{@link #contentLength()} returns {@code -1} so OkHttp uses
 * streaming/chunked transfer encoding.
 *
 * <p>{@link #toString()} omits the PNG path and any image data.
 */
final class StreamingPngChatRequestBody extends RequestBody {

	private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json");

	private static final byte[] PNG_SIGNATURE = { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };

	private static final int COPY_BUFFER_SIZE = 8192;

	private final Path pngPath;
	private final long expectedPngByteSize;
	private final long maxImageBytes;
	private final String model;
	private final String prompt;
	private final int maxTokens;
	private final double temperature;
	private final double topP;
	private final ObjectMapper objectMapper;

	StreamingPngChatRequestBody(Path pngPath, long expectedPngByteSize, long maxImageBytes, String model,
			String prompt, int maxTokens, double temperature, double topP, ObjectMapper objectMapper) {
		if (pngPath == null) {
			throw new IllegalArgumentException("pngPath must not be null");
		}
		if (expectedPngByteSize <= 0) {
			throw new IllegalArgumentException("expectedPngByteSize must be positive");
		}
		if (maxImageBytes <= 0) {
			throw new IllegalArgumentException("maxImageBytes must be positive");
		}
		if (model == null || model.isBlank()) {
			throw new IllegalArgumentException("model must not be blank");
		}
		if (prompt == null || prompt.isBlank()) {
			throw new IllegalArgumentException("prompt must not be blank");
		}
		this.pngPath = pngPath;
		this.expectedPngByteSize = expectedPngByteSize;
		this.maxImageBytes = maxImageBytes;
		this.model = model;
		this.prompt = prompt;
		this.maxTokens = maxTokens;
		this.temperature = temperature;
		this.topP = topP;
		this.objectMapper = objectMapper;
	}

	@Override
	public MediaType contentType() {
		return JSON_MEDIA_TYPE;
	}

	@Override
	public long contentLength() {
		return -1L;
	}

	@Override
	public void writeTo(BufferedSink sink) throws IOException {
		// 1. Verify the PNG file size before streaming.
		long actualSize;
		try {
			actualSize = Files.size(this.pngPath);
		}
		catch (IOException e) {
			throw new OcrException(Code.OCR_IMAGE_UNAVAILABLE, e);
		}
		if (actualSize <= 0) {
			throw new OcrException(Code.OCR_IMAGE_INVALID);
		}
		if (actualSize != this.expectedPngByteSize) {
			throw new OcrException(Code.OCR_IMAGE_INVALID);
		}
		if (actualSize > this.maxImageBytes) {
			throw new OcrException(Code.OCR_IMAGE_INVALID);
		}

		// 2. Write the JSON prefix.
		//    Produces: {"model":"<MODEL>","messages":[{"role":"user","content":[{"type":"image_url","image_url":{"url":"
		sink.write(buildJsonPrefix());

		// 3. Write the data URL scheme prefix.
		sink.write("data:image/png;base64,".getBytes(StandardCharsets.UTF_8));

		// 4. Stream the PNG file through Base64 with an 8192-byte buffer.
		OutputStream nonClosingSink = new NonClosingOutputStream(sink);
		OutputStream base64Stream = Base64.getEncoder().wrap(nonClosingSink);
		long rawByteCount = 0L;
		byte[] copyBuffer = new byte[COPY_BUFFER_SIZE];

		try (InputStream in = Files.newInputStream(this.pngPath)) {
			// Validate the PNG signature from the first 8 raw bytes.
			byte[] signatureBuffer = new byte[PNG_SIGNATURE.length];
			int sigBytesRead = 0;
			while (sigBytesRead < PNG_SIGNATURE.length) {
				int n = in.read(signatureBuffer, sigBytesRead, PNG_SIGNATURE.length - sigBytesRead);
				if (n == -1) {
					throw new OcrException(Code.OCR_IMAGE_INVALID);
				}
				sigBytesRead += n;
			}
			for (int i = 0; i < PNG_SIGNATURE.length; i++) {
				if (signatureBuffer[i] != PNG_SIGNATURE[i]) {
					throw new OcrException(Code.OCR_IMAGE_INVALID);
				}
			}
			base64Stream.write(signatureBuffer, 0, PNG_SIGNATURE.length);
			rawByteCount += PNG_SIGNATURE.length;
			if (rawByteCount > this.maxImageBytes) {
				throw new OcrException(Code.OCR_IMAGE_INVALID);
			}

			int bytesRead;
			while ((bytesRead = in.read(copyBuffer)) != -1) {
				rawByteCount += bytesRead;
				if (rawByteCount > this.expectedPngByteSize) {
					throw new OcrException(Code.OCR_IMAGE_INVALID);
				}
				if (rawByteCount > this.maxImageBytes) {
					throw new OcrException(Code.OCR_IMAGE_INVALID);
				}
				base64Stream.write(copyBuffer, 0, bytesRead);
			}
		}
		catch (IOException e) {
			throw new OcrException(Code.OCR_IMAGE_UNAVAILABLE, e);
		}
		finally {
			try {
				base64Stream.close();
			}
			catch (IOException ignored) {
			}
		}

		// 5. At EOF, require the raw byte count to equal the expected size.
		if (rawByteCount != this.expectedPngByteSize) {
			throw new OcrException(Code.OCR_IMAGE_INVALID);
		}

		// 6. Write the JSON suffix and flush.
		//    Produces: "}}},{"type":"text","text":"<PROMPT>"}]},"temperature":<T>,"top_p":<P>,"max_tokens":<N>,"n":1,"stream":false}
		sink.write(buildJsonSuffix());
		sink.flush();
	}

	private byte[] buildJsonPrefix() {
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			Writer w = new OutputStreamWriter(baos, StandardCharsets.UTF_8);
			w.write("{\"model\":\"");
			writeEscapedString(w, this.model);
			w.write("\",\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"");
			w.flush();
			return baos.toByteArray();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private byte[] buildJsonSuffix() {
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			Writer w = new OutputStreamWriter(baos, StandardCharsets.UTF_8);
			// Close url string, image_url inner obj, image_url content part.
			w.write("\"");
			w.write("}}");
			// Text content part.
			w.write(",{\"type\":\"text\",\"text\":\"");
			writeEscapedString(w, this.prompt);
			// Close text part, content array, user msg, messages array.
			w.write("\"}]}],\"temperature\":");
			w.write(Double.toString(this.temperature));
			w.write(",\"top_p\":");
			w.write(Double.toString(this.topP));
			w.write(",\"max_tokens\":");
			w.write(Integer.toString(this.maxTokens));
			w.write(",\"n\":1,\"stream\":false}");
			w.flush();
			return baos.toByteArray();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private void writeEscapedString(Writer writer, String value) throws IOException {
		String jsonStr = this.objectMapper.writeValueAsString(value);
		writer.write(jsonStr.substring(1, jsonStr.length() - 1));
	}

	@Override
	public String toString() {
		return "StreamingPngChatRequestBody[model=" + this.model + ", expectedPngByteSize=" + this.expectedPngByteSize
				+ ", maxImageBytes=" + this.maxImageBytes + "]";
	}

	private static final class NonClosingOutputStream extends OutputStream {

		private final OutputStream delegate;

		NonClosingOutputStream(BufferedSink sink) {
			this.delegate = sink.outputStream();
		}

		@Override
		public void write(int b) throws IOException {
			this.delegate.write(b);
		}

		@Override
		public void write(byte[] buf, int off, int len) throws IOException {
			this.delegate.write(buf, off, len);
		}

		@Override
		public void close() {
		}

	}

}
