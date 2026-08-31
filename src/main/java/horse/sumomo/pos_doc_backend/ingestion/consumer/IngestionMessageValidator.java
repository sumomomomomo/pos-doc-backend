package horse.sumomo.pos_doc_backend.ingestion.consumer;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.stereotype.Component;

import horse.sumomo.pos_doc_backend.ingestion.messaging.IngestionRequestedMessage;

/**
 * Validates a raw AMQP {@link Message} and parses the
 * {@link IngestionRequestedMessage} body with strict checks.
 *
 * <p>The body and headers are inspected before the JSON is parsed. Any
 * violation raises {@link ConsumerException} with
 * {@link ConsumerException.Code#MESSAGE_INVALID}; the listener treats this
 * as a nonretryable failure so the message reaches the DLQ rather than
 * re-entering the queue.
 */
@Component
public class IngestionMessageValidator {

	private static final Set<String> ALLOWED_PROPERTIES = Set.of("eventId", "jobId", "posRecordId",
			"schemaVersion", "occurredAt");

	private static final String EXPECTED_MESSAGE_TYPE = "INGESTION_REQUESTED";
	private static final String EXPECTED_CONTENT_TYPE = "application/json";
	private static final String EXPECTED_CONTENT_ENCODING = "UTF-8";

	private final int maxMessageBytes;

	public IngestionMessageValidator(horse.sumomo.pos_doc_backend.ingestion.api.ConsumerProperties properties) {
		this.maxMessageBytes = properties.getMaxMessageBytes();
	}

	/**
	 * Validates the AMQP envelope and the parsed JSON body.
	 *
	 * @throws ConsumerException with {@link ConsumerException.Code#MESSAGE_INVALID}
	 *             on every contract violation
	 */
	public IngestionRequestedMessage validate(Message message) {
		Objects.requireNonNull(message, "message must not be null");

		MessageProperties props = message.getMessageProperties();
		if (props == null) {
			throw invalid("missing AMQP properties");
		}

		String contentType = props.getContentType();
		if (contentType == null
				|| !contentType.toLowerCase(Locale.ROOT).startsWith(EXPECTED_CONTENT_TYPE)) {
			throw invalid("unsupported content type");
		}
		String encoding = props.getContentEncoding();
		if (encoding != null && !EXPECTED_CONTENT_ENCODING.equalsIgnoreCase(encoding)) {
			throw invalid("unsupported content encoding");
		}
		MessageDeliveryMode outgoing = props.getDeliveryMode();
		MessageDeliveryMode incoming = props.getReceivedDeliveryMode();
		if (outgoing != MessageDeliveryMode.PERSISTENT && incoming != MessageDeliveryMode.PERSISTENT) {
			throw invalid("delivery mode must be persistent");
		}
		String type = props.getType();
		if (!EXPECTED_MESSAGE_TYPE.equals(type)) {
			throw invalid("AMQP type must be INGESTION_REQUESTED");
		}

		byte[] body = message.getBody();
		if (body == null) {
			throw invalid("empty body");
		}
		if (body.length > this.maxMessageBytes) {
			throw invalid("body exceeds the configured maximum size");
		}

		Map<String, Object> raw;
		try {
			raw = JsonObjectParser.parse(body);
		}
		catch (IOException e) {
			throw invalid("body is not valid JSON");
		}

		if (raw == null || raw.isEmpty()) {
			throw invalid("body must contain the required fields");
		}

		Set<String> unexpected = new LinkedHashSet<>(raw.keySet());
		unexpected.removeAll(ALLOWED_PROPERTIES);
		if (!unexpected.isEmpty()) {
			throw invalid("body contains unexpected fields");
		}
		for (String required : ALLOWED_PROPERTIES) {
			if (!raw.containsKey(required) || raw.get(required) == null) {
				throw invalid("body is missing required field " + required);
			}
		}

		UUID eventId;
		UUID jobId;
		UUID posRecordId;
		int schemaVersion;
		Instant occurredAt;
		try {
			eventId = asUuid(raw.get("eventId"));
			jobId = asUuid(raw.get("jobId"));
			posRecordId = asUuid(raw.get("posRecordId"));
			schemaVersion = asInt(raw.get("schemaVersion"));
			occurredAt = asInstant(raw.get("occurredAt"));
		}
		catch (IllegalArgumentException e) {
			throw invalid("body field has an invalid type");
		}

		if (schemaVersion != IngestionRequestedMessage.SCHEMA_VERSION) {
			throw invalid("schemaVersion must be " + IngestionRequestedMessage.SCHEMA_VERSION);
		}

		String messageId = props.getMessageId();
		if (messageId == null || !eventId.toString().equals(messageId)) {
			throw invalid("messageId must equal eventId");
		}
		String correlationId = props.getCorrelationId();
		if (correlationId == null || !jobId.toString().equals(correlationId)) {
			throw invalid("correlationId must equal jobId");
		}

		try {
			return new IngestionRequestedMessage(eventId, jobId, posRecordId, schemaVersion, occurredAt);
		}
		catch (NullPointerException | IllegalArgumentException e) {
			throw invalid("body fields failed canonical validation");
		}
	}

	private static ConsumerException invalid(String detail) {
		return new ConsumerException(ConsumerException.Code.MESSAGE_INVALID, detail);
	}

	private static UUID asUuid(Object value) {
		if (!(value instanceof String s)) {
			throw new IllegalArgumentException("expected UUID string");
		}
		try {
			return UUID.fromString(s);
		}
		catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("invalid UUID: " + s);
		}
	}

	private static int asInt(Object value) {
		if (value instanceof Number n) {
			return n.intValue();
		}
		if (value instanceof String s) {
			try {
				return Integer.parseInt(s);
			}
			catch (NumberFormatException e) {
				throw new IllegalArgumentException("invalid int: " + s);
			}
		}
		throw new IllegalArgumentException("expected integer");
	}

	private static Instant asInstant(Object value) {
		if (!(value instanceof String s)) {
			throw new IllegalArgumentException("expected ISO-8601 instant string");
		}
		try {
			return Instant.parse(s);
		}
		catch (RuntimeException e) {
			throw new IllegalArgumentException("invalid instant: " + s);
		}
	}

	/**
	 * Tiny JSON object parser that supports only the message-body shape:
	 * flat object with string, number, boolean, and null values. Sufficient
	 * for the bounded message body; bringing in Jackson here would create a
	 * dependency cycle with the application-context-managed mapper. Public
	 * for unit testing.
	 */
	static final class JsonObjectParser {

		private final String src;
		private int pos;

		private JsonObjectParser(String src) {
			this.src = src;
		}

		static Map<String, Object> parse(byte[] body) throws IOException {
			String s = new String(body, java.nio.charset.StandardCharsets.UTF_8).trim();
			JsonObjectParser p = new JsonObjectParser(s);
			Map<String, Object> out = p.parseObject();
			p.skipWhitespace();
			if (p.pos != p.src.length()) {
				throw new IOException("trailing content after JSON object");
			}
			return out;
		}

		private Map<String, Object> parseObject() throws IOException {
			Map<String, Object> out = new LinkedHashMap<>();
			skipWhitespace();
			if (pos >= src.length() || src.charAt(pos) != '{') {
				throw new IOException("expected '{' at index " + pos);
			}
			pos++;
			skipWhitespace();
			if (pos < src.length() && src.charAt(pos) == '}') {
				pos++;
				return out;
			}
			while (true) {
				skipWhitespace();
				if (pos >= src.length() || src.charAt(pos) != '"') {
					throw new IOException("expected string key at index " + pos);
				}
				String key = readString();
				skipWhitespace();
				if (pos >= src.length() || src.charAt(pos) != ':') {
					throw new IOException("expected ':' at index " + pos);
				}
				pos++;
				skipWhitespace();
				Object value = readValue();
				out.put(key, value);
				skipWhitespace();
				if (pos < src.length() && src.charAt(pos) == ',') {
					pos++;
					continue;
				}
				if (pos < src.length() && src.charAt(pos) == '}') {
					pos++;
					return out;
				}
				throw new IOException("expected ',' or '}' at index " + pos);
			}
		}

		private String readString() throws IOException {
			if (pos >= src.length() || src.charAt(pos) != '"') {
				throw new IOException("expected '\"' at index " + pos);
			}
			pos++;
			StringBuilder sb = new StringBuilder();
			while (pos < src.length()) {
				char c = src.charAt(pos);
				if (c == '"') {
					pos++;
					return sb.toString();
				}
				if (c == '\\') {
					if (pos + 1 >= src.length()) {
						throw new IOException("dangling escape");
					}
					char esc = src.charAt(pos + 1);
					switch (esc) {
						case '"' -> sb.append('"');
						case '\\' -> sb.append('\\');
						case '/' -> sb.append('/');
						case 'b' -> sb.append('\b');
						case 'f' -> sb.append('\f');
						case 'n' -> sb.append('\n');
						case 'r' -> sb.append('\r');
						case 't' -> sb.append('\t');
						default -> throw new IOException("unsupported escape: \\" + esc);
					}
					pos += 2;
					continue;
				}
				sb.append(c);
				pos++;
			}
			throw new IOException("unterminated string");
		}

		private Object readValue() throws IOException {
			if (pos >= src.length()) {
				throw new IOException("unexpected end of input");
			}
			char c = src.charAt(pos);
			if (c == '"') {
				return readString();
			}
			if (c == 't' && src.startsWith("true", pos)) {
				pos += 4;
				return Boolean.TRUE;
			}
			if (c == 'f' && src.startsWith("false", pos)) {
				pos += 5;
				return Boolean.FALSE;
			}
			if (c == 'n' && src.startsWith("null", pos)) {
				pos += 4;
				return null;
			}
			if (c == '-' || (c >= '0' && c <= '9')) {
				return readNumber();
			}
			throw new IOException("unsupported value at index " + pos);
		}

		private Number readNumber() throws IOException {
			int start = pos;
			if (src.charAt(pos) == '-') {
				pos++;
			}
			while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
				pos++;
			}
			boolean isFloat = false;
			if (pos < src.length() && src.charAt(pos) == '.') {
				isFloat = true;
				pos++;
				while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
					pos++;
				}
			}
			String num = src.substring(start, pos);
			try {
				return isFloat ? (Number) Double.parseDouble(num) : Long.parseLong(num);
			}
			catch (NumberFormatException e) {
				throw new IOException("invalid number: " + num);
			}
		}

		private void skipWhitespace() {
			while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
				pos++;
			}
		}
	}

}