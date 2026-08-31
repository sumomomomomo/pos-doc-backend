package horse.sumomo.pos_doc_backend.ingestion.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;

import horse.sumomo.pos_doc_backend.ingestion.api.ConsumerProperties;
import horse.sumomo.pos_doc_backend.ingestion.consumer.IngestionConsumerService.IngestionMessageIdentifiers;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for {@link IngestionTerminalRecoverer}.
 *
 * <p>The recoverer is the boundary between the AMQP layer and the
 * database: it must never mutate a row unless the {@code jobId} /
 * {@code posRecordId} pair came from a strictly validated body AND
 * {@link IngestionTerminalFailureService#verifyRelationship} confirmed
 * the two are linked. The AMQP {@code messageId} is the
 * {@code eventId}, not the {@code posRecordId}; these tests prove the
 * recoverer never reads it as such.
 */
class IngestionTerminalRecovererTest {

	private IngestionMessageValidator validator;
	private IngestionTerminalFailureService failureService;
	private IngestionTerminalRecoverer recoverer;

	@BeforeEach
	void setUp() {
		ConsumerProperties props = new ConsumerProperties(true, 1, 1, 3, 1000L, 2.0, 5000L, 4096);
		JsonMapper mapper = JsonMapper.builder()
				.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
				.build();
		this.validator = new IngestionMessageValidator(props, mapper);
		this.failureService = mock(IngestionTerminalFailureService.class);
		this.recoverer = new IngestionTerminalRecoverer(this.validator, this.failureService);
	}

	@Test
	void validBodyWithVerifiedRelationshipMarksTerminal() {
		UUID eventId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID posRecordId = UUID.randomUUID();
		Message message = buildMessage(eventId, jobId, posRecordId, Instant.parse("2026-01-02T03:04:05Z"));
		ConsumerException cause = new ConsumerException(ConsumerException.Code.EXTRACTION_TRANSIENT_FAILURE);

		when(this.failureService.verifyRelationship(jobId, posRecordId)).thenReturn(true);

		this.recoverer.recover(message, cause);

		verify(this.failureService, times(1)).verifyRelationship(jobId, posRecordId);
		verify(this.failureService, times(1)).markTerminal(
				eq(new IngestionMessageIdentifiers(jobId, posRecordId)),
				any(ConsumerException.class));
	}

	@Test
	void malformedBodySkipsDatabaseMutation() {
		// Body is invalid JSON; validator rejects with MESSAGE_INVALID.
		MessageProperties props = new MessageProperties();
		props.setContentType("application/json");
		props.setContentEncoding("UTF-8");
		props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
		props.setType("INGESTION_REQUESTED");
		UUID eventId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		props.setMessageId(eventId.toString());
		props.setCorrelationId(jobId.toString());
		Message message = new Message("not-json".getBytes(StandardCharsets.UTF_8), props);

		ConsumerException cause = new ConsumerException(ConsumerException.Code.EXTRACTION_TRANSIENT_FAILURE);

		this.recoverer.recover(message, cause);

		// No relationship verification, no terminal mutation.
		verify(this.failureService, never()).verifyRelationship(any(UUID.class), any(UUID.class));
		verify(this.failureService, never()).markTerminal(any(), any());
	}

	@Test
	void unverifiedRelationshipSkipsDatabaseMutation() {
		// Strict validation passes but the jobId/posRecordId pair is
		// not linked in the database. The recoverer must not write.
		UUID eventId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID posRecordId = UUID.randomUUID();
		Message message = buildMessage(eventId, jobId, posRecordId, Instant.parse("2026-01-02T03:04:05Z"));
		ConsumerException cause = new ConsumerException(ConsumerException.Code.EXTRACTION_TRANSIENT_FAILURE);

		when(this.failureService.verifyRelationship(jobId, posRecordId)).thenReturn(false);

		this.recoverer.recover(message, cause);

		verify(this.failureService, times(1)).verifyRelationship(jobId, posRecordId);
		verify(this.failureService, never()).markTerminal(any(), any());
	}

	@Test
	void doesNotReadAmqpMessageIdAsPosRecordId() {
		// Set messageId to a totally unrelated UUID and verify the
		// recoverer never tries to use it as a posRecordId.
		UUID eventId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID posRecordId = UUID.randomUUID();
		UUID hijackedPosRecordId = UUID.randomUUID();
		MessageProperties props = new MessageProperties();
		props.setContentType("application/json");
		props.setContentEncoding("UTF-8");
		props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
		props.setType("INGESTION_REQUESTED");
		props.setMessageId(eventId.toString());
		props.setCorrelationId(jobId.toString());
		// Put a UUID that has nothing to do with posRecordId in the
		// messageId header: a recovering implementation must never
		// confuse messageId with posRecordId.
		String body = "{\"eventId\":\"" + eventId + "\",\"jobId\":\"" + jobId + "\","
				+ "\"posRecordId\":\"" + posRecordId + "\",\"schemaVersion\":1,"
				+ "\"occurredAt\":\"2026-01-02T03:04:05Z\"}";
		Message message = new Message(body.getBytes(StandardCharsets.UTF_8), props);

		ConsumerException cause = new ConsumerException(ConsumerException.Code.EXTRACTION_TRANSIENT_FAILURE);

		when(this.failureService.verifyRelationship(jobId, posRecordId)).thenReturn(true);

		this.recoverer.recover(message, cause);

		// The recoverer asked about the body-derived posRecordId, not
		// about any UUID it could have read from the AMQP messageId.
		verify(this.failureService, times(1)).verifyRelationship(jobId, posRecordId);
		verify(this.failureService, never()).verifyRelationship(any(UUID.class), eq(hijackedPosRecordId));

		// The terminal transition carries the body-derived IDs.
		verify(this.failureService, times(1)).markTerminal(
				eq(new IngestionMessageIdentifiers(jobId, posRecordId)),
				any(ConsumerException.class));
	}

	@Test
	void categorizesNonConsumerExceptionAsTransientFailure() {
		// If the cause is not a ConsumerException, the recoverer still
		// needs a usable category code. The safest default is
		// EXTRACTION_TRANSIENT_FAILURE.
		UUID eventId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID posRecordId = UUID.randomUUID();
		Message message = buildMessage(eventId, jobId, posRecordId, Instant.parse("2026-01-02T03:04:05Z"));
		RuntimeException raw = new RuntimeException("uncaught boom");

		when(this.failureService.verifyRelationship(jobId, posRecordId)).thenReturn(true);

		this.recoverer.recover(message, raw);

		verify(this.failureService, times(1)).markTerminal(
				eq(new IngestionMessageIdentifiers(jobId, posRecordId)),
				any(ConsumerException.class));
	}

	@Test
	void unwrapsConsumerExceptionFromCauseChain() {
		// The listener may wrap ConsumerException in another layer
		// (e.g. Spring's ListenerExecutionFailedException). The
		// recoverer must unwrap it before passing to the failure
		// service so the JobStatus.fail() code is correct.
		UUID eventId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID posRecordId = UUID.randomUUID();
		Message message = buildMessage(eventId, jobId, posRecordId, Instant.parse("2026-01-02T03:04:05Z"));
		ConsumerException root = new ConsumerException(ConsumerException.Code.EXTRACTION_STATE_CONFLICT);
		RuntimeException wrapper = new RuntimeException("wrapper", root);

		when(this.failureService.verifyRelationship(jobId, posRecordId)).thenReturn(true);

		this.recoverer.recover(message, wrapper);

		org.mockito.ArgumentCaptor<ConsumerException> captor =
				org.mockito.ArgumentCaptor.forClass(ConsumerException.class);
		verify(this.failureService, times(1)).markTerminal(
				eq(new IngestionMessageIdentifiers(jobId, posRecordId)),
				captor.capture());
		assertNotNull(captor.getValue());
		assertEquals(ConsumerException.Code.EXTRACTION_STATE_CONFLICT, captor.getValue().getCode());
	}

	private static Message buildMessage(UUID eventId, UUID jobId, UUID posRecordId, Instant occurredAt) {
		String body = "{\"eventId\":\"" + eventId + "\",\"jobId\":\"" + jobId + "\","
				+ "\"posRecordId\":\"" + posRecordId + "\",\"schemaVersion\":1,"
				+ "\"occurredAt\":\"" + occurredAt + "\"}";
		MessageProperties props = new MessageProperties();
		props.setContentType("application/json");
		props.setContentEncoding("UTF-8");
		props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
		props.setType("INGESTION_REQUESTED");
		props.setMessageId(eventId.toString());
		props.setCorrelationId(jobId.toString());
		return new Message(body.getBytes(StandardCharsets.UTF_8), props);
	}
}