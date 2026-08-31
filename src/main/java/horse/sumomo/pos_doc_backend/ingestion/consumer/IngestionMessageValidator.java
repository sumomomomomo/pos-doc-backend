package horse.sumomo.pos_doc_backend.ingestion.consumer;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import horse.sumomo.pos_doc_backend.ingestion.api.ConsumerProperties;
import horse.sumomo.pos_doc_backend.ingestion.messaging.IngestionRequestedMessage;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Validates a raw AMQP {@link Message} and parses the
 * {@link IngestionRequestedMessage} body with strict checks.
 *
 * <p>The body and headers are inspected before the JSON is parsed. Any
 * violation raises {@link ConsumerException} with
 * {@link ConsumerException.Code#MESSAGE_INVALID}; the listener treats this
 * as a nonretryable failure so the message reaches the DLQ rather than
 * re-entering the queue.
 *
 * <p>JSON parsing is delegated to the dedicated ingestion-message
 * Jackson mapper exposed by
 * {@link IngestionMessageJsonMapperConfiguration}, which has
 * {@link StreamReadFeature#STRICT_DUPLICATE_DETECTION} enabled so a body
 * that carries the same field name twice is rejected up-front rather
 * than being silently overwritten. Exactly the five contract fields
 * ({@code eventId, jobId, posRecordId, schemaVersion, occurredAt}) are
 * accepted; any other field, including a duplicate, is a contract
 * violation.
 */
@Component
public class IngestionMessageValidator {

	private static final Set<String> ALLOWED_PROPERTIES = Set.of("eventId", "jobId", "posRecordId",
			"schemaVersion", "occurredAt");

	private static final String EXPECTED_MESSAGE_TYPE = "INGESTION_REQUESTED";
	private static final String EXPECTED_CONTENT_TYPE = "application/json";
	private static final String EXPECTED_CONTENT_ENCODING = "UTF-8";

	private final int maxMessageBytes;
	private final JsonMapper jsonMapper;

	public IngestionMessageValidator(ConsumerProperties properties,
			@Qualifier(IngestionMessageJsonMapperConfiguration.INGESTION_MESSAGE_JSON_MAPPER) JsonMapper jsonMapper) {
		this.maxMessageBytes = properties.getMaxMessageBytes();
		this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper must not be null");
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
		if (props.getDeliveryMode() != MessageDeliveryMode.PERSISTENT
				&& props.getReceivedDeliveryMode() != MessageDeliveryMode.PERSISTENT) {
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

		Map<String, String> raw = parseBodyAsFlatObject(body);
		if (raw == null || raw.isEmpty()) {
			throw invalid("body must contain the required fields");
		}

		// Exactly the contract fields, in any order. Any other key is
		// rejected. STRICT_DUPLICATE_DETECTION on the underlying
		// mapper already prevents duplicate keys at parse time.
		if (raw.size() != ALLOWED_PROPERTIES.size()) {
			throw invalid("body must contain exactly the five contract fields");
		}
		for (String required : ALLOWED_PROPERTIES) {
			if (!raw.containsKey(required)) {
				throw invalid("body is missing required field " + required);
			}
		}

		UUID eventId;
		UUID jobId;
		UUID posRecordId;
		int schemaVersion;
		Instant occurredAt;
		try {
			eventId = UUID.fromString(raw.get("eventId"));
			jobId = UUID.fromString(raw.get("jobId"));
			posRecordId = UUID.fromString(raw.get("posRecordId"));
			schemaVersion = Integer.parseInt(raw.get("schemaVersion"));
			occurredAt = Instant.parse(raw.get("occurredAt"));
		}
		catch (RuntimeException e) {
			throw invalid("body field has an invalid type or format");
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

	/**
	 * Parses the body as a flat JSON object whose values are all
	 * strings. Duplicate keys raise {@link ConsumerException} with
	 * {@code MESSAGE_INVALID} so a hostile producer cannot smuggle two
	 * {@code jobId} fields and have the second silently win.
	 *
	 * <p>The configured mapper must have
	 * {@link StreamReadFeature#STRICT_DUPLICATE_DETECTION} enabled; the
	 * explicit {@code LinkedHashMap.put(...)} duplicate check is a
	 * defense-in-depth measure in case the feature is ever disabled
	 * by a future configuration change.
	 */
	private Map<String, String> parseBodyAsFlatObject(byte[] body) {
		JsonNode root;
		try {
			root = this.jsonMapper.readTree(body);
		}
		catch (JacksonException e) {
			throw invalid("body is not valid JSON");
		}
		if (root == null || !root.isObject()) {
			throw invalid("body must be a JSON object");
		}
		LinkedHashMap<String, String> out = new LinkedHashMap<>();
		for (Map.Entry<String, JsonNode> entry : root.properties()) {
			String name = entry.getKey();
			JsonNode value = entry.getValue();
			if (value == null || value.isContainer()) {
				throw invalid("body field " + name + " must be a scalar");
			}
			String stringValue;
			if (value.isNull()) {
				throw invalid("body field " + name + " must not be null");
			}
			else if (value.isString()) {
				stringValue = value.asString();
			}
			else if (value.isBoolean() || value.isNumber()) {
				// Numbers and booleans come through as their textual
				// canonical form (e.g. "42", "true") which is what the
				// downstream parsers expect.
				stringValue = value.asString();
			}
			else {
				throw invalid("body field " + name + " has an unsupported JSON type");
			}
			if (out.put(name, stringValue) != null) {
				throw invalid("body contains duplicate field " + name);
			}
		}
		return out;
	}

	private static ConsumerException invalid(String detail) {
		return new ConsumerException(ConsumerException.Code.MESSAGE_INVALID, detail);
	}
}