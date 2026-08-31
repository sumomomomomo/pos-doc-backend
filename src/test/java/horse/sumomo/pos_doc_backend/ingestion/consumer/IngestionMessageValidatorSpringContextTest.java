package horse.sumomo.pos_doc_backend.ingestion.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import horse.sumomo.pos_doc_backend.ingestion.messaging.IngestionRequestedMessage;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spring-context test for {@link IngestionMessageValidator}.
 *
 * <p>This test boots a real Spring application context (with the
 * consumer disabled so it does not need a broker) and verifies that:
 *
 * <ol>
 *   <li>The injection-message {@link JsonMapper} bean is registered
 *       under the documented qualifier.</li>
 *   <li>The production mapper has
 *       {@code STRICT_DUPLICATE_DETECTION} enabled, so a body that
 *       smuggles the same field name twice is rejected up-front.</li>
 *   <li>The validator wired against the production mapper surfaces
 *       the duplicate as a {@link ConsumerException} with
 *       {@link ConsumerException.Code#MESSAGE_INVALID} so the listener
 *       routes the message to the DLQ rather than silently letting
 *       the second value win.</li>
 * </ol>
 *
 * <p>The unit test {@code IngestionMessageValidatorTest} builds its
 * own mapper to exercise the validator in isolation; this test
 * verifies that Spring actually wires the same configuration in
 * production.
 */
@SpringBootTest(properties = {
		"app.messaging.outbox.enabled=false",
		"app.ingestion.consumer.enabled=false"
})
class IngestionMessageValidatorSpringContextTest {

	@Autowired
	private IngestionMessageValidator validator;

	@Autowired
	@Qualifier(IngestionMessageJsonMapperConfiguration.INGESTION_MESSAGE_JSON_MAPPER)
	private JsonMapper ingestionMessageJsonMapper;

	@Test
	void ingestionMessageMapperBeanIsExposed() {
		assertNotNull(this.ingestionMessageJsonMapper,
				"Ingestion-message JsonMapper bean must be present in the Spring context");
	}

	@Test
	void productionMapperHasStrictDuplicateDetectionEnabled() {
		// Use the production-wired mapper directly to verify the feature
		// is on. We construct a body that contains the same field twice
		// and assert that readTree fails rather than silently returning
		// a JsonNode with the second value.
		UUID eventId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID hijackedJobId = UUID.randomUUID();
		String body = "{\"eventId\":\"" + eventId + "\",\"jobId\":\"" + jobId + "\","
				+ "\"jobId\":\"" + hijackedJobId + "\","
				+ "\"posRecordId\":\"" + UUID.randomUUID() + "\","
				+ "\"schemaVersion\":1,"
				+ "\"occurredAt\":\"2026-01-02T03:04:05Z\"}";

		tools.jackson.databind.JsonNode parsed;
		try {
			parsed = this.ingestionMessageJsonMapper.readTree(body.getBytes(StandardCharsets.UTF_8));
		}
		catch (RuntimeException expected) {
			// STRICT_DUPLICATE_DETECTION is enabled: parsing must fail
			// before any value is exposed.
			return;
		}
		throw new AssertionError("Production ingestion mapper accepted a body with duplicate jobId; "
				+ "STRICT_DUPLICATE_DETECTION is not enabled. Parsed = " + parsed);
	}

	@Test
	void productionWiredValidatorRejectsDuplicateFields() {
		// The validator must be wired against the production mapper.
		// Use it on a duplicate-bearing body and prove the validator
		// itself raises MESSAGE_INVALID.
		UUID eventId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID posRecordId = UUID.randomUUID();
		UUID hijackedJobId = UUID.randomUUID();
		String body = "{\"eventId\":\"" + eventId + "\",\"jobId\":\"" + jobId + "\","
				+ "\"jobId\":\"" + hijackedJobId + "\","
				+ "\"posRecordId\":\"" + posRecordId + "\","
				+ "\"schemaVersion\":1,"
				+ "\"occurredAt\":\"2026-01-02T03:04:05Z\"}";
		MessageProperties props = amqpProps(eventId, jobId);
		Message amqp = new Message(body.getBytes(StandardCharsets.UTF_8), props);

		ConsumerException e = assertThrows(ConsumerException.class, () -> this.validator.validate(amqp));
		assertEquals(ConsumerException.Code.MESSAGE_INVALID, e.getCode());
		String detail = e.getMessage() == null ? "" : e.getMessage();
		assertTrue(!detail.contains(hijackedJobId.toString()),
				"hijacked jobId leaked into exception message: " + detail);
	}

	@Test
	void productionWiredValidatorAcceptsValidMessage() {
		UUID eventId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID posRecordId = UUID.randomUUID();
		Instant occurredAt = Instant.parse("2026-01-02T03:04:05Z");
		String body = "{\"eventId\":\"" + eventId + "\",\"jobId\":\"" + jobId + "\","
				+ "\"posRecordId\":\"" + posRecordId + "\","
				+ "\"schemaVersion\":1,"
				+ "\"occurredAt\":\"" + occurredAt + "\"}";
		MessageProperties props = amqpProps(eventId, jobId);
		Message amqp = new Message(body.getBytes(StandardCharsets.UTF_8), props);

		IngestionRequestedMessage parsed = this.validator.validate(amqp);
		assertEquals(eventId, parsed.eventId());
		assertEquals(jobId, parsed.jobId());
		assertEquals(posRecordId, parsed.posRecordId());
		assertEquals(occurredAt, parsed.occurredAt());
	}

	private static MessageProperties amqpProps(UUID eventId, UUID jobId) {
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