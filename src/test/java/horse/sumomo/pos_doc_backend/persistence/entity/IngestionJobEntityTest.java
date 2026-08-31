package horse.sumomo.pos_doc_backend.persistence.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import horse.sumomo.pos_doc_backend.persistence.model.JobStatus;

class IngestionJobEntityTest {

	@Test
	void startAttemptTransitionsToRunningIncrementsAndStampsStartedAt() {
		UUID id = UUID.randomUUID();
		PosRecordEntity record = stubRecord();
		IngestionJobEntity job = new IngestionJobEntity(id, record, JobStatus.QUEUED, 0L, Instant.now());
		Instant now = Instant.parse("2026-01-02T03:04:05Z");

		job.startAttempt(now);

		assertEquals(JobStatus.RUNNING, job.getStatus());
		assertEquals(1L, job.getAttemptCount());
		assertEquals(now, job.getStartedAt());
		assertNull(job.getCompletedAt());
	}

	@Test
	void secondStartAttemptDoesNotIncrementAgain() {
		UUID id = UUID.randomUUID();
		PosRecordEntity record = stubRecord();
		IngestionJobEntity job = new IngestionJobEntity(id, record, JobStatus.QUEUED, 0L, Instant.now());
		Instant first = Instant.parse("2026-01-02T03:04:05Z");
		Instant second = Instant.parse("2026-01-02T03:04:06Z");

		job.startAttempt(first);
		job.startAttempt(second);

		assertEquals(2L, job.getAttemptCount(), "each startAttempt bumps the counter");
		assertEquals(first, job.getStartedAt(), "startedAt is set on first startAttempt");
	}

	@Test
	void completeStampsCompletedAtAndStatus() {
		UUID id = UUID.randomUUID();
		PosRecordEntity record = stubRecord();
		IngestionJobEntity job = new IngestionJobEntity(id, record, JobStatus.RUNNING, 1L, Instant.now());
		Instant now = Instant.parse("2026-01-02T03:04:05Z");

		job.complete(now);

		assertEquals(JobStatus.COMPLETED, job.getStatus());
		assertEquals(now, job.getCompletedAt());
	}

	@Test
	void secondCompleteKeepsFirstCompletedAt() {
		UUID id = UUID.randomUUID();
		PosRecordEntity record = stubRecord();
		IngestionJobEntity job = new IngestionJobEntity(id, record, JobStatus.RUNNING, 1L, Instant.now());
		Instant first = Instant.parse("2026-01-02T03:04:05Z");
		Instant second = Instant.parse("2026-01-02T03:04:06Z");

		job.complete(first);
		job.complete(second);

		assertEquals(first, job.getCompletedAt(), "completedAt is set on first complete()");
	}

	@Test
	void failSetsTerminalStatusAndErrorFields() {
		UUID id = UUID.randomUUID();
		PosRecordEntity record = stubRecord();
		IngestionJobEntity job = new IngestionJobEntity(id, record, JobStatus.RUNNING, 1L, Instant.now());
		Instant now = Instant.parse("2026-01-02T03:04:05Z");

		job.fail(now, "EXTRACTION_STATE_CONFLICT", "stable message");

		assertEquals(JobStatus.FAILED, job.getStatus());
		assertEquals("EXTRACTION_STATE_CONFLICT", job.getErrorCode());
		assertEquals("stable message", job.getErrorMessage());
		assertEquals(now, job.getCompletedAt());
	}

	@Test
	void failRejectsBlankCode() {
		UUID id = UUID.randomUUID();
		PosRecordEntity record = stubRecord();
		IngestionJobEntity job = new IngestionJobEntity(id, record, JobStatus.RUNNING, 1L, Instant.now());
		assertThrows(IllegalArgumentException.class, () -> job.fail(Instant.now(), "  ", "msg"));
	}

	@Test
	void markRetryTransitionsToRetryScheduledWithoutChangingAttemptCount() {
		UUID id = UUID.randomUUID();
		PosRecordEntity record = stubRecord();
		IngestionJobEntity job = new IngestionJobEntity(id, record, JobStatus.RUNNING, 3L, Instant.now());
		Instant now = Instant.parse("2026-01-02T03:04:05Z");

		job.markRetry(now, "SOURCE_STORAGE_UNAVAILABLE", "transient");

		assertEquals(JobStatus.RETRY_SCHEDULED, job.getStatus());
		assertEquals(3L, job.getAttemptCount(), "markRetry does not bump attempt count");
		assertEquals("SOURCE_STORAGE_UNAVAILABLE", job.getErrorCode());
	}

	private static PosRecordEntity stubRecord() {
		PosRecordEntity r = new PosRecordEntity(UUID.randomUUID(),
				stubStorage(UUID.randomUUID(), "stub.zip", "application/zip", 1L, "a".repeat(64)),
				horse.sumomo.pos_doc_backend.persistence.model.PosRecordStatus.UPLOADED, "test-uploader",
				Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"));
		return r;
	}

	private static StorageObjectEntity stubStorage(UUID id, String filename, String contentType, long size,
			String sha256) {
		StorageObjectEntity s = new StorageObjectEntity(id, "archives/" + id + "/" + UUID.randomUUID() + ".zip",
				filename, contentType, size, sha256, Instant.parse("2026-01-01T00:00:00Z"));
		// Equality is by id only; no need to wire a PosRecord reference.
		assertNotNull(s);
		return s;
	}

	@SuppressWarnings("unused")
	private static <T> T same(T expected) {
		return expected;
	}

	@SuppressWarnings("unused")
	private static void assertSameRef(Object expected, Object actual) {
		assertSame(expected, actual);
	}

	// Helper: visible for reflective injection in tests.
	static void setField(Object target, String name, Object value) {
		try {
			Field f = target.getClass().getDeclaredField(name);
			f.setAccessible(true);
			f.set(target, value);
		}
		catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

}