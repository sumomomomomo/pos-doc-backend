package horse.sumomo.pos_doc_backend.persistence;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import tools.jackson.databind.json.JsonMapper;

import horse.sumomo.pos_doc_backend.ingestion.application.IntakeDatabaseService;
import horse.sumomo.pos_doc_backend.ingestion.application.IntakeException;
import horse.sumomo.pos_doc_backend.ingestion.application.UploadCommand;
import horse.sumomo.pos_doc_backend.ingestion.messaging.IngestionRequestedMessage;
import horse.sumomo.pos_doc_backend.persistence.entity.OutboxEventEntity;
import horse.sumomo.pos_doc_backend.persistence.repository.OutboxEventRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real SQLite transaction tests for the outbox and the single intake
 * transaction (Task 4-5, step 20).
 *
 * <p>Uses a real temporary database and the real repositories. The outbox
 * relay is disabled so no broker is contacted.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {
 	"app.messaging.outbox.enabled=false",
 	"app.ingestion.consumer.enabled=false"
})
class OutboxIntakeTransactionIntegrationTest {

	private static final String SHA = "0000000000000000000000000000000000000000000000000000000000000000";

	@Autowired
	private IntakeDatabaseService databaseService;

	@Autowired
	private OutboxEventRepository outboxEventRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private JsonMapper jsonMapper;

	@DynamicPropertySource
	static void sqliteUrl(DynamicPropertyRegistry registry) throws Exception {
		Path dbFile = Files.createTempFile("pos-doc-outbox-test", ".db");
		dbFile.toFile().deleteOnExit();
		Path.of(dbFile.toString() + "-wal").toFile().deleteOnExit();
		Path.of(dbFile.toString() + "-shm").toFile().deleteOnExit();
		registry.add("SQLITE_URL", () -> "jdbc:sqlite:" + dbFile);
	}

	@Test
	void v2MigrationCreatesOutboxTableAndPendingIndex() {
		Boolean tableExists = this.jdbcTemplate.queryForObject(
				"SELECT count(*) > 0 FROM sqlite_master WHERE type = 'table' AND name = 'outbox_event'", Boolean.class);
		assertTrue(tableExists, "outbox_event table must exist");

		Boolean indexExists = this.jdbcTemplate.queryForObject(
				"SELECT count(*) > 0 FROM sqlite_master WHERE type = 'index' AND name = 'ix_outbox_pending'",
				Boolean.class);
		assertTrue(indexExists, "ix_outbox_pending index must exist");
	}

	@Test
	void intakeCommitsAllFourRowsTogether() throws Exception {
		UploadCommand command = command("EREF-TXN-001", null);

		this.databaseService.persist(command);

		assertRowExists("storage_object", command.storageObjectId());
		assertRowExists("pos_record", command.posRecordId());
		assertRowExists("ingestion_job", command.jobId());
		assertRowExists("outbox_event", command.outboxEventId());

		// Object key uses only the generated UUID structure.
		String storedKey = this.jdbcTemplate.queryForObject(
				"SELECT object_key FROM storage_object WHERE id = ?", String.class,
				command.storageObjectId().toString());
		assertEquals("archives/" + command.posRecordId() + "/" + command.storageObjectId() + ".zip", storedKey);
	}

	@Test
	void storedOutboxJsonContainsOnlyTheFiveAllowedFields() throws Exception {
		UploadCommand command = command("EREF-TXN-002", "POLICY-TXN-002");
		this.databaseService.persist(command);

		String payload = this.jdbcTemplate.queryForObject(
				"SELECT payload_json FROM outbox_event WHERE id = ?", String.class, command.outboxEventId().toString());
		Map<String, Object> node = this.jsonMapper.readValue(payload, Map.class);

		assertEquals(5, node.size(), "outbox payload must contain exactly the five allowed fields");
		assertTrue(node.containsKey("eventId"));
		assertTrue(node.containsKey("jobId"));
		assertTrue(node.containsKey("posRecordId"));
		assertTrue(node.containsKey("schemaVersion"));
		assertTrue(node.containsKey("occurredAt"));
		assertEquals(1, node.get("schemaVersion"));
	}

	@Test
	void jobStartsQueuedWithZeroAttemptsAndRecordUploaded() throws Exception {
		UploadCommand command = command("EREF-TXN-003", null);
		this.databaseService.persist(command);

		JobState job = this.jdbcTemplate.queryForObject(
				"SELECT status, attempt_count, error_code, error_message, started_at_epoch_ms, "
						+ "completed_at_epoch_ms FROM ingestion_job WHERE id = ?",
				(rs, rowNum) -> new JobState(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4),
						readNullableLong(rs, 5), readNullableLong(rs, 6)),
				command.jobId().toString());
		assertEquals("QUEUED", job.status);
		assertEquals(0, job.attemptCount);
		assertNull(job.errorCode);
		assertNull(job.errorMessage);
		assertNull(job.startedAt);
		assertNull(job.completedAt);

		String recordStatus = this.jdbcTemplate.queryForObject(
				"SELECT status FROM pos_record WHERE id = ?", String.class, command.posRecordId().toString());
		assertEquals("UPLOADED", recordStatus);
	}

	@Test
	void duplicateErefRollsBackAllRowsFromThatTransaction() throws Exception {
		UploadCommand first = command("EREF-TXN-DUP", null);
		this.databaseService.persist(first);

		UploadCommand second = command("EREF-TXN-DUP", null);
		IntakeException e = assertThrows(IntakeException.class, () -> this.databaseService.persist(second));
		assertEquals(IntakeException.Code.DUPLICATE_EREF_NUMBER, e.getCode());

		assertRowMissing("storage_object", second.storageObjectId());
		assertRowMissing("pos_record", second.posRecordId());
		assertRowMissing("ingestion_job", second.jobId());
		assertRowMissing("outbox_event", second.outboxEventId());
	}

	@Test
	void duplicatePolicyNumberRollsBackAllRowsFromThatTransaction() throws Exception {
		UploadCommand first = command("EREF-TXN-POL-1", "POLICY-TXN-DUP");
		this.databaseService.persist(first);

		UploadCommand second = command("EREF-TXN-POL-2", "POLICY-TXN-DUP");
		IntakeException e = assertThrows(IntakeException.class, () -> this.databaseService.persist(second));
		assertEquals(IntakeException.Code.DUPLICATE_POLICY_NUMBER, e.getCode());

		assertRowMissing("storage_object", second.storageObjectId());
		assertRowMissing("pos_record", second.posRecordId());
		assertRowMissing("ingestion_job", second.jobId());
		assertRowMissing("outbox_event", second.outboxEventId());
	}

	@Test
	void recordFailureFollowsTheExactRetrySchedule() throws Exception {
		OutboxEventEntity event = newEvent("EREF-TXN-SCHED", null, Instant.parse("2026-01-01T00:00:00Z"));
		this.outboxEventRepository.saveAndFlush(event);
		UUID id = event.getId();

		OutboxEventEntity e1 = this.outboxEventRepository.findById(id).orElseThrow();
		e1.recordFailure(Instant.parse("2026-01-01T00:00:00Z"));
		this.outboxEventRepository.saveAndFlush(e1);
		assertEquals(1, this.outboxEventRepository.findById(id).orElseThrow().getAttemptCount());
		assertEquals(Instant.parse("2026-01-01T00:00:01Z"),
				this.outboxEventRepository.findById(id).orElseThrow().getNextAttemptAt());

		OutboxEventEntity e2 = this.outboxEventRepository.findById(id).orElseThrow();
		e2.recordFailure(Instant.parse("2026-01-01T00:00:10Z"));
		this.outboxEventRepository.saveAndFlush(e2);
		assertEquals(Instant.parse("2026-01-01T00:00:15Z"),
				this.outboxEventRepository.findById(id).orElseThrow().getNextAttemptAt());

		OutboxEventEntity e3 = this.outboxEventRepository.findById(id).orElseThrow();
		e3.recordFailure(Instant.parse("2026-01-01T00:00:20Z"));
		this.outboxEventRepository.saveAndFlush(e3);
		assertEquals(Instant.parse("2026-01-01T00:00:50Z"),
				this.outboxEventRepository.findById(id).orElseThrow().getNextAttemptAt());

		OutboxEventEntity e4 = this.outboxEventRepository.findById(id).orElseThrow();
		e4.recordFailure(Instant.parse("2026-01-01T00:00:30Z"));
		this.outboxEventRepository.saveAndFlush(e4);
		assertEquals(Instant.parse("2026-01-01T00:01:30Z"),
				this.outboxEventRepository.findById(id).orElseThrow().getNextAttemptAt());

		OutboxEventEntity e5 = this.outboxEventRepository.findById(id).orElseThrow();
		e5.recordFailure(Instant.parse("2026-01-01T00:00:40Z"));
		this.outboxEventRepository.saveAndFlush(e5);
		assertEquals(Instant.parse("2026-01-01T00:05:40Z"),
				this.outboxEventRepository.findById(id).orElseThrow().getNextAttemptAt());
	}

	@Test
	void markPublishedIsIdempotentAndExcludesTheRowFromPendingQueries() throws Exception {
		Instant created = Instant.parse("2026-01-01T00:00:00Z");
		OutboxEventEntity event = newEvent("EREF-TXN-PUB", null, created);
		this.outboxEventRepository.saveAndFlush(event);
		UUID id = event.getId();

		assertTrue(this.outboxEventRepository.findPendingDue(created.plusSeconds(1), PageRequest.of(0, 100)).stream()
				.anyMatch(e -> e.getId().equals(id)), "unpublished event must be pending");

		OutboxEventEntity e = this.outboxEventRepository.findById(id).orElseThrow();
		e.markPublished(Instant.parse("2026-01-01T01:00:00Z"));
		this.outboxEventRepository.saveAndFlush(e);

		OutboxEventEntity eAgain = this.outboxEventRepository.findById(id).orElseThrow();
		eAgain.markPublished(Instant.parse("2026-01-01T02:00:00Z"));
		this.outboxEventRepository.saveAndFlush(eAgain);
		assertEquals(Instant.parse("2026-01-01T01:00:00Z"),
				this.outboxEventRepository.findById(id).orElseThrow().getPublishedAt(),
				"markPublished must keep the first publication instant");

		assertFalse(this.outboxEventRepository.findPendingDue(created.plus(1, ChronoUnit.DAYS), PageRequest.of(0, 100)).stream()
				.anyMatch(e2 -> e2.getId().equals(id)), "published event must not be pending");
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	private OutboxEventEntity newEvent(String eref, String policy, Instant created) throws Exception {
		UploadCommand command = command(eref, policy);
		this.databaseService.persist(command);
		OutboxEventEntity event = this.outboxEventRepository.findById(command.outboxEventId()).orElseThrow();
		return event;
	}

	private UploadCommand command(String eref, String policy) throws Exception {
		Instant requestedAt = Instant.parse("2026-01-01T00:00:00Z");
		UUID storageObjectId = UUID.randomUUID();
		UUID posRecordId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID outboxEventId = UUID.randomUUID();
		String objectKey = "archives/" + posRecordId + "/" + storageObjectId + ".zip";

		IngestionRequestedMessage message = IngestionRequestedMessage.of(outboxEventId, jobId, posRecordId,
				requestedAt);
		String payload = this.jsonMapper.writeValueAsString(message);

		Path spooledPath = Path.of("spooled-not-needed-" + posRecordId);
		return new UploadCommand(eref + ".zip", eref, policy, "application/zip", spooledPath, 128L, SHA, 1,
				"AUTH_NOT_IMPLEMENTED", requestedAt, storageObjectId, posRecordId, jobId, outboxEventId, objectKey,
				payload);
	}

	private static Long readNullableLong(java.sql.ResultSet rs, int column) throws java.sql.SQLException {
		long value = rs.getLong(column);
		return rs.wasNull() ? null : value;
	}

	private void assertRowExists(String table, UUID id) {
		Integer count = this.jdbcTemplate.queryForObject(
				"SELECT count(*) FROM " + table + " WHERE id = ?", Integer.class, id.toString());
		assertNotNull(count);
		assertEquals(1, count, "row must exist in " + table);
	}

	private void assertRowMissing(String table, UUID id) {
		Integer count = this.jdbcTemplate.queryForObject(
				"SELECT count(*) FROM " + table + " WHERE id = ?", Integer.class, id.toString());
		assertEquals(0, count, "row must be rolled back in " + table);
	}

	private record JobState(String status, int attemptCount, String errorCode, String errorMessage,
			Long startedAt, Long completedAt) {
	}

}
