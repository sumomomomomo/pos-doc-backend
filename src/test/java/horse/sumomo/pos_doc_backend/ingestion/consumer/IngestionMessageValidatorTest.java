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
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;

class IngestionMessageValidatorTest {

	private IngestionMessageValidator validator;

	@BeforeEach
	void setUp() {
		JsonMapper mapper = JsonMapper.builder()
				.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
				.build();
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
				"hijacked second value must not be surfaced in the exception detail");
	}

	@Test
	void missingFieldIsRejected() {
		UUID eventId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		String body = "{\"eventId\":\"" + eventId + "\",\"jobId\":\"" + jobId + "\","
				+ "\"posRecordId\":\"" + UUID.randomUUID() + "\","
				+ "\"occurredAt\":\"2026-01-02T03:04:05Z\"}";
		MessageProperties props = amqpProps(eventId, jobId);
		Message message = new Message(body.getBytes(StandardCharsets.UTF_8), props);

		assertThrows(ConsumerException.class, () -> this.validator.validate(message));
	}

	@Test
	void wrongSchemaVersionIsRejected() {
		UUID eventId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID posRecordId = UUID.randomUUID();
		Message message = build(eventId, jobId, posRecordId, Instant.parse("2026-01-02T03:04:05Z"), 2);

		assertThrows(ConsumerException.class, () -> this.validator.validate(message));
	}

	@Test
	void messageIdMismatchIsRejected() {
		UUID eventId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID posRecordId = UUID.randomUUID();
		UUID wrongMessageId = UUID.randomUUID();
		String body = "{\"eventId\":\"" + eventId + "\",\"jobId\":\"" + jobId + "\","
				+ "\"posRecordId\":\"" + posRecordId + "\",\"schemaVersion\":1,"
				+ "\"occurredAt\":\"2026-01-02T03:04:05Z\"}";
		MessageProperties props = amqpProps(wrongMessageId, jobId);
		Message message = new Message(body.getBytes(StandardCharsets.UTF_8), props);

		assertThrows(ConsumerException.class, () -> this.validator.validate(message));
	}

	@Test
	void correlationIdMismatchIsRejected() {
		UUID eventId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID posRecordId = UUID.randomUUID();
		UUID wrongCorrelationId = UUID.randomUUID();
		String body = "{\"eventId\":\"" + eventId + "\",\"jobId\":\"" + jobId + "\","
				+ "\"posRecordId\":\"" + posRecordId + "\",\"schemaVersion\":1,"
				+ "\"occurredAt\":\"2026-01-02T03:04:05Z\"}";
		MessageProperties props = amqpProps(eventId, wrongCorrelationId);
		Message message = new Message(body.getBytes(StandardCharsets.UTF_8), props);

		assertThrows(ConsumerException.class, () -> this.validator.validate(message));
	}

	@Test
	void amqpTypeMismatchIsRejected() {
		UUID eventId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID posRecordId = UUID.randomUUID();
		Message message = build(eventId, jobId, posRecordId, Instant.parse("2026-01-02T03:04:05Z"), 1);
		MessageProperties props = message.getMessageProperties();
		props.setType("INGESTION_OTHER");

		ConsumerException e = assertThrows(ConsumerException.class, () -> this.validator.validate(message));
		assertEquals(ConsumerException.Code.MESSAGE_INVALID, e.getCode());
	}

	@Test
	void nonPersistentDeliveryModeIsRejected() {
		UUID eventId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID posRecordId = UUID.randomUUID();
		Message message = build(eventId, jobId, posRecordId, Instant.parse("2026-01-02T03:04:05Z"), 1);
		message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.NON_PERSISTENT);

		assertThrows(ConsumerException.class, () -> this.validator.validate(message));
	}

	@Test
	void oversizeBodyIsRejected() {
		UUID eventId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID posRecordId = UUID.randomUUID();
		MessageProperties props = amqpProps(eventId, jobId);
		// Build a body that is large enough to exceed the configured 4096 cap.
		StringBuilder sb = new StringBuilder("{\"eventId\":\"").append(eventId).append("\",");
		while (sb.length() < 8192) {
			sb.append("\"pad\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\",");
		}
		sb.append("}");
		Message message = new Message(sb.toString().getBytes(StandardCharsets.UTF_8), props);

		ConsumerException e = assertThrows(ConsumerException.class, () -> this.validator.validate(message));
		assertEquals(ConsumerException.Code.MESSAGE_INVALID, e.getCode());
	}

	@Test
	void emptyBodyIsRejected() {
		UUID eventId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		MessageProperties props = amqpProps(eventId, jobId);
		Message message = new Message(new byte[0], props);

		assertThrows(ConsumerException.class, () -> this.validator.validate(message));
	}

	@Test
	void invalidJsonIsRejected() {
		UUID eventId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		MessageProperties props = amqpProps(eventId, jobId);
		Message message = new Message("not json".getBytes(StandardCharsets.UTF_8), props);

		assertThrows(ConsumerException.class, () -> this.validator.validate(message));
	}

	@Test
	void nullFieldsAreRejected() {
		UUID eventId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID posRecordId = UUID.randomUUID();
		String body = "{\"eventId\":null,\"jobId\":\"" + jobId + "\","
				+ "\"posRecordId\":\"" + posRecordId + "\",\"schemaVersion\":1,"
				+ "\"occurredAt\":\"2026-01-02T03:04:05Z\"}";
		MessageProperties props = amqpProps(eventId, jobId);
		Message message = new Message(body.getBytes(StandardCharsets.UTF_8), props);

		assertThrows(ConsumerException.class, () -> this.validator.validate(message));
	}

	@Test
	void malformedUuidIsRejected() {
		UUID eventId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		String body = "{\"eventId\":\"not-a-uuid\",\"jobId\":\"" + jobId + "\","
				+ "\"posRecordId\":\"" + UUID.randomUUID() + "\",\"schemaVersion\":1,"
				+ "\"occurredAt\":\"2026-01-02T03:04:05Z\"}";
		MessageProperties props = amqpProps(eventId, jobId);
		Message message = new Message(body.getBytes(StandardCharsets.UTF_8), props);

		assertThrows(ConsumerException.class, () -> this.validator.validate(message));
	}

	@Test
	void theExceptionMessageCarriesNoPii() {
		UUID eventId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID posRecordId = UUID.randomUUID();
		String body = "{\"eventId\":\"" + eventId + "\",\"jobId\":\"" + jobId + "\","
				+ "\"posRecordId\":\"" + posRecordId + "\",\"schemaVersion\":1,"
				+ "\"occurredAt\":\"2026-01-02T03:04:05Z\",\"extra\":\"EREF-SECRET\"}";
		MessageProperties props = amqpProps(eventId, jobId);
		Message message = new Message(body.getBytes(StandardCharsets.UTF_8), props);

		ConsumerException e = assertThrows(ConsumerException.class, () -> this.validator.validate(message));
		String throwableMessage = e.getMessage() == null ? "" : e.getMessage();
		assertTrue(!throwableMessage.contains("EREF-SECRET"));
		assertTrue(!throwableMessage.contains(eventId.toString()));
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