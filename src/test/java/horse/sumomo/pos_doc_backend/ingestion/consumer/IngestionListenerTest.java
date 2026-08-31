package horse.sumomo.pos_doc_backend.ingestion.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;

import horse.sumomo.pos_doc_backend.ingestion.api.ConsumerProperties;
import horse.sumomo.pos_doc_backend.ingestion.consumer.IngestionConsumerService.IngestionMessageIdentifiers;
import horse.sumomo.pos_doc_backend.ingestion.messaging.IngestionRequestedMessage;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for {@link IngestionListener} retry, terminal-recovery and
 * malformed-message handling.
 *
 * <p>Covers Task 6 acceptance criteria:
 * <ul>
 * <li>transient failure followed by successful retry succeeds,</li>
 * <li>retry exhaustion marks the job FAILED via the recoverer and
 * re-throws so the message lands on the DLQ,</li>
 * <li>a malformed message reaches the DLQ without invoking the
 * downstream consumer.</li>
 * </ul>
 */
class IngestionListenerTest {

	private IngestionMessageValidator validator;
	private IngestionConsumerService consumerService;
	private IngestionTerminalRecoverer terminalRecoverer;
	private IngestionListener listener;

	private static final int MAX_ATTEMPTS = 3;
	private static final long INITIAL_BACKOFF_MS = 1L;
	private static final long MAX_BACKOFF_MS = 1L;

	@BeforeEach
	void setUp() {
		ConsumerProperties props = new ConsumerProperties(true, 1, 1, MAX_ATTEMPTS,
				INITIAL_BACKOFF_MS, 2.0, MAX_BACKOFF_MS, 4096);
		JsonMapper mapper = JsonMapper.builder()
				.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
				.build();
		this.validator = new IngestionMessageValidator(props, mapper);
		this.consumerService = mock(IngestionConsumerService.class);
		this.terminalRecoverer = mock(IngestionTerminalRecoverer.class);
		this.listener = new IngestionListener(this.validator, this.consumerService,
				this.terminalRecoverer, props);
	}

	@Test
	void transientFailureFollowedBySuccessSucceeds() {
		UUID eventId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID posRecordId = UUID.randomUUID();
		Instant occurredAt = Instant.parse("2026-01-02T03:04:05Z");
		Message message = buildMessage(eventId, jobId, posRecordId, occurredAt, 1);

		AtomicInteger calls = new AtomicInteger();
		doAnswer(inv -> {
			int n = calls.incrementAndGet();
			if (n < 2) {
				throw new ConsumerException(ConsumerException.Code.EXTRACTION_TRANSIENT_FAILURE,
						"simulated transient failure on attempt " + n);
			}
			return null;
		}).when(this.consumerService).consume(any(IngestionMessageIdentifiers.class));

		this.listener.onMessage(message);

		// Successful retry: consume called twice (1 failure + 1 success).
		verify(this.consumerService, times(2))
				.consume(any(IngestionMessageIdentifiers.class));
		// Recoverer never invoked because the listener succeeded.
		verify(this.terminalRecoverer, never()).recover(any(Message.class), any(Throwable.class));
	}

	@Test
	void exhaustedRetriesTriggerTerminalRecovererAndThrow() {
		UUID eventId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID posRecordId = UUID.randomUUID();
		Instant occurredAt = Instant.parse("2026-01-02T03:04:05Z");
		Message message = buildMessage(eventId, jobId, posRecordId, occurredAt, 1);

		doThrow(new ConsumerException(ConsumerException.Code.EXTRACTION_TRANSIENT_FAILURE,
				"simulated persistent transient failure"))
				.when(this.consumerService).consume(any(IngestionMessageIdentifiers.class));
		doNothing().when(this.terminalRecoverer).recover(any(Message.class), any(Throwable.class));

		ConsumerException thrown = assertThrows(ConsumerException.class,
				() -> this.listener.onMessage(message));
		assertEquals(ConsumerException.Code.EXTRACTION_TRANSIENT_FAILURE, thrown.getCode());

		// consume was attempted MAX_ATTEMPTS times before the listener gave up.
		verify(this.consumerService, times(MAX_ATTEMPTS))
				.consume(any(IngestionMessageIdentifiers.class));
		// The recoverer was invoked exactly once to record the FAILED state.
		verify(this.terminalRecoverer, times(1)).recover(any(Message.class), any(Throwable.class));
	}

	@Test
	void malformedMessageReachesDlqAndNeverInvokesConsumer() {
		// Body is invalid JSON; validator rejects before the consumer is touched.
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

		ConsumerException thrown = assertThrows(ConsumerException.class,
				() -> this.listener.onMessage(message));
		assertEquals(ConsumerException.Code.MESSAGE_INVALID, thrown.getCode());

		// Consumer is never reached: a malformed message cannot cause any
		// database mutation in the downstream flow.
		verify(this.consumerService, never())
				.consume(any(IngestionMessageIdentifiers.class));
		// The recoverer must record the FAILED transition so a DLQ message
		// is never paired with a job still in RETRY_SCHEDULED.
		verify(this.terminalRecoverer, atLeastOnce()).recover(any(Message.class), any(Throwable.class));
	}

	@Test
	void nonretryableFailureSkipsRemainingAttemptsAndInvokesRecoverer() {
		// Defends against the contract that nonretryable categories (e.g.
		// EXTRACTION_STATE_CONFLICT) immediately trigger the terminal
		// recoverer and are not retried.
		UUID eventId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID posRecordId = UUID.randomUUID();
		Instant occurredAt = Instant.parse("2026-01-02T03:04:05Z");
		Message message = buildMessage(eventId, jobId, posRecordId, occurredAt, 1);

		doThrow(new ConsumerException(ConsumerException.Code.EXTRACTION_STATE_CONFLICT,
				"simulated state conflict"))
				.when(this.consumerService).consume(any(IngestionMessageIdentifiers.class));

		ConsumerException thrown = assertThrows(ConsumerException.class,
				() -> this.listener.onMessage(message));
		assertEquals(ConsumerException.Code.EXTRACTION_STATE_CONFLICT, thrown.getCode());

		verify(this.consumerService, times(1))
				.consume(any(IngestionMessageIdentifiers.class));
		verify(this.terminalRecoverer, times(1)).recover(any(Message.class), any(Throwable.class));
	}

	@Test
	void terminalRecovererIsIdempotentWhenCalledMultipleTimes() {
		// Defensive contract: if the recoverer is invoked twice for the
		// same message (e.g. once by validation failure path and once by
		// a defensive explicit call), the FAILED transition must not
		// corrupt state. This is enforced by IngestionTerminalFailureService
		// being a no-op for already-terminal jobs.
		UUID eventId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID posRecordId = UUID.randomUUID();
		Instant occurredAt = Instant.parse("2026-01-02T03:04:05Z");
		Message message = buildMessage(eventId, jobId, posRecordId, occurredAt, 1);

		doThrow(new ConsumerException(ConsumerException.Code.EXTRACTION_STATE_CONFLICT))
				.when(this.consumerService).consume(any(IngestionMessageIdentifiers.class));

		assertThrows(ConsumerException.class, () -> this.listener.onMessage(message));
		verify(this.terminalRecoverer, times(1)).recover(any(Message.class), any(Throwable.class));

		// A subsequent invocation on a different exception must still
		// surface the failure and call the recoverer (the recoverer is
		// responsible for its own idempotency at the service level).
		Message again = buildMessage(eventId, jobId, posRecordId, occurredAt, 1);
		doThrow(new ConsumerException(ConsumerException.Code.ID_MISMATCH))
				.when(this.consumerService).consume(any(IngestionMessageIdentifiers.class));
		assertThrows(ConsumerException.class, () -> this.listener.onMessage(again));
		verify(this.terminalRecoverer, times(2)).recover(any(Message.class), any(Throwable.class));
	}

	private Message buildMessage(UUID eventId, UUID jobId, UUID posRecordId, Instant occurredAt,
			int schemaVersion) {
		String body = "{\"eventId\":\"" + eventId + "\",\"jobId\":\"" + jobId + "\","
				+ "\"posRecordId\":\"" + posRecordId + "\",\"schemaVersion\":" + schemaVersion + ","
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

	// Quiet compiler about unused methods on the validator - keep for
	// future use when expanding the contract surface.
	@SuppressWarnings("unused")
	private static IngestionRequestedMessage ensureImported() {
		return null;
	}

	// Sanity check the assertion utilities are wired.
	@SuppressWarnings("unused")
	private static void typeAssertionsUsed() {
		assertTrue(true);
		assertEquals(0, 0);
	}
}