package horse.sumomo.pos_doc_backend.ingestion.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;

import horse.sumomo.pos_doc_backend.ingestion.api.ConsumerProperties;
import horse.sumomo.pos_doc_backend.ingestion.messaging.IngestionRequestedMessage;
import tools.jackson.databind.json.JsonMapper;

class IngestionMessageValidatorTest {

	private IngestionMessageValidator validator;

	@BeforeEach
	void setUp() {
		// Use the same mapper construction path as production so the
		// unit test exercises the real STRICT_DUPLICATE_DETECTION
		// configuration that Spring wires in production.
		JsonMapper mapper = new IngestionMessageJsonMapperConfiguration()
				.ingestionMessageJsonMapper();
		this.validator = new IngestionMessageValidator(
				new ConsumerProperties(true, 1, 1, 3, 1000L, 2.0, 5000L, 4096),
				mapper);
	}

	@Test
	void validMessageIsAccepted() {
		UUID eventId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID posRecordId = UUID.randomUUID();
		Instant occurredAt = Instant.parse("2026-01-02T03:04:05Z");
		Message message = build(eventId, jobId, posRecordId, occurredAt, 1);

		IngestionRequestedMessage parsed = this.validator.validate(message);

		assertEquals(eventId, parsed.eventId());
		assertEquals(jobId, parsed.jobId());
		assertEquals(posRecordId, parsed.posRecordId());
		assertEquals(1, parsed.schemaVersion());
		assertEquals(occurredAt, parsed.occurredAt());
	}

	@Test
	void extraFieldIsRejected() {
		UUID eventId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID posRecordId = UUID.randomUUID();
		String body = "{\"eventId\":\"" + eventId + "\",\"jobId\":\"" + jobId + "\","
				+ "\"posRecordId\":\"" + posRecordId + "\",\"schemaVersion\":1,"
				+ "\"occurredAt\":\"2026-01-02T03:04:05Z\",\"extra\":42}";
		MessageProperties props = amqpProps(eventId, jobId);
		Message message = new Message(body.getBytes(StandardCharsets.UTF_8), props);

		ConsumerException e = assertThrows(ConsumerException.class, () -> this.validator.validate(message));
		assertEquals(ConsumerException.Code.MESSAGE_INVALID, e.getCode());
	}

	@Test
	void duplicateContractFieldIsRejected() {
		// The configured mapper has STRICT_DUPLICATE_DETECTION enabled.
		// A body that smuggles the same field name twice must be rejected
		// before any field value is read; the validator must surface it
		// as a MESSAGE_INVALID so the listener routes the message to the
		// DLQ rather than silently letting the second value win.
		UUID eventId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID posRecordId = UUID.randomUUID();
		UUID hijackedJobId = UUID.randomUUID();
		String body = "{\"eventId\":\"" + eventId + "\",\"jobId\":\"" + jobId + "\","
				+ "\"jobId\":\"" + hijackedJobId + "\","
				+ "\"posRecordId\":\"" + posRecordId + "\",\"schemaVersion\":1,"
				+ "\"occurredAt\":\"2026-01-02T03:04:05Z\"}";
		MessageProperties props = amqpProps(eventId, jobId);
		Message amqp = new Message(body.getBytes(StandardCharsets.UTF_8), props);

		ConsumerException e = assertThrows(ConsumerException.class, () -> this.validator.validate(amqp));
		assertEquals(ConsumerException.Code.MESSAGE_INVALID, e.getCode());
		// The hijacked value must never have been parsed.
		String detail = e.getMessage() == null ? "" : e.getMessage();
		assertTrue(!detail.contains(hijackedJobId.toString()),
				"hijacked jobId leaked into exception message: " + detail);
	}

	private Message build(UUID eventId, UUID jobId, UUID posRecordId, Instant occurredAt, int schemaVersion) {
		String body = "{\"eventId\":\"" + eventId + "\",\"jobId\":\"" + jobId + "\","
				+ "\"posRecordId\":\"" + posRecordId + "\",\"schemaVersion\":" + schemaVersion + ","
				+ "\"occurredAt\":\"" + occurredAt + "\"}";
		MessageProperties props = amqpProps(eventId, jobId);
		return new Message(body.getBytes(StandardCharsets.UTF_8), props);
	}

	private MessageProperties amqpProps(UUID eventId, UUID jobId) {
		MessageProperties props = new MessageProperties();
		props.setContentType("application/json");
		props.setContentEncoding("UTF-8");
		props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
		props.setType("INGESTION_REQUESTED");
		props.setMessageId(eventId.toString());
		props.setCorrelationId(jobId.toString());
		return props;
	}
}