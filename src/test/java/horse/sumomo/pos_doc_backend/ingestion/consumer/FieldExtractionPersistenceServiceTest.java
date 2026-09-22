package horse.sumomo.pos_doc_backend.ingestion.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import horse.sumomo.pos_doc_backend.persistence.entity.PosFieldExtractionEntity;
import horse.sumomo.pos_doc_backend.persistence.entity.PosFieldExtractionId;
import horse.sumomo.pos_doc_backend.persistence.model.ExtractionField;
import horse.sumomo.pos_doc_backend.persistence.model.ExtractionOutcome;

/**
 * Integration tests for {@link FieldExtractionPersistenceService} against a real
 * temporary SQLite database: idempotent outcome upserts, null-guarded business
 * field updates, and the completion-gate invariants.
 */
@SpringBootTest(properties = {
		"app.messaging.outbox.enabled=false",
		"app.ingestion.consumer.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FieldExtractionPersistenceServiceTest {

	private static final String MODEL = "task12-test-model";
	private static final Instant NOW = Instant.parse("2026-01-02T03:04:05Z");

	@Autowired
	private FieldExtractionPersistenceService persistenceService;

	@Autowired
	private JdbcTemplate jdbc;

	@DynamicPropertySource
	static void sqliteUrl(DynamicPropertyRegistry registry) throws Exception {
		Path dbFile = Files.createTempFile("pos-doc-field-extract-persist-test", ".db");
		dbFile.toFile().deleteOnExit();
		registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbFile.toAbsolutePath());
	}

	private record Setup(UUID recordId, UUID jobId, List<UUID> documentIds) {
	}

	/**
	 * Creates a record (with the given status and business fields), the given
	 * PDF documents (in sequence order), and a job for the record.
	 */
	private Setup setupRecord(String recordStatus, List<String> pdfFilenames, List<String> docStatuses,
			String policyholder, String consultant, String createDate) {
		UUID recordId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID archiveStorageId = UUID.randomUUID();
		long now = System.currentTimeMillis();

		jdbc.update(
				"INSERT INTO storage_object (id, object_key, original_filename, content_type, byte_size, sha256, "
						+ "created_at_epoch_ms) VALUES (?,?,?,?,?,?,?)",
				archiveStorageId.toString(), "archives/" + recordId + "/archive.zip", "archive.zip",
				"application/zip", 100, "a".repeat(64), now);
		jdbc.update(
				"INSERT INTO pos_record (id, source_archive_id, status, uploaded_by, uploaded_at_epoch_ms, "
						+ "updated_at_epoch_ms, version, policyholder_name, consultant_name, policy_create_date) "
						+ "VALUES (?,?,?,?,?,?,?,?,?,?)",
				recordId.toString(), archiveStorageId.toString(), recordStatus, "test-user", now, now, 1,
				policyholder, consultant, createDate);

		List<UUID> docIds = new java.util.ArrayList<>();
		for (int i = 0; i < pdfFilenames.size(); i++) {
			UUID docId = UUID.randomUUID();
			UUID docStorageId = UUID.randomUUID();
			jdbc.update(
					"INSERT INTO storage_object (id, object_key, original_filename, content_type, byte_size, "
							+ "sha256, created_at_epoch_ms) VALUES (?,?,?,?,?,?,?)",
					docStorageId.toString(), "documents/" + recordId + "/" + i + ".pdf", pdfFilenames.get(i),
					"application/pdf", 100, "b".repeat(64), now);
			jdbc.update(
					"INSERT INTO pos_document (id, pos_record_id, storage_object_id, sequence_number, "
							+ "document_type, processing_status) VALUES (?,?,?,?,?,?)",
					docId.toString(), recordId.toString(), docStorageId.toString(), i, "OTHER", docStatuses.get(i));
			docIds.add(docId);
		}

		jdbc.update(
				"INSERT INTO ingestion_job (id, pos_record_id, status, attempt_count, created_at_epoch_ms, "
						+ "version) VALUES (?,?,?,?,?,?)",
				jobId.toString(), recordId.toString(), "RUNNING", 1, now, 1);

		return new Setup(recordId, jobId, docIds);
	}

	private PosFieldExtractionEntity resolved(UUID docId, ExtractionField field, String value, int attempts) {
		return new PosFieldExtractionEntity(new PosFieldExtractionId(docId, field.name(), 3),
				ExtractionOutcome.RESOLVED, value, MODEL, "stop", attempts, null, NOW);
	}

	private PosFieldExtractionEntity unknown(UUID docId, ExtractionField field, int attempts) {
		return new PosFieldExtractionEntity(new PosFieldExtractionId(docId, field.name(), 3),
				ExtractionOutcome.UNKNOWN, null, MODEL, "stop", attempts, null, NOW);
	}

	private PosFieldExtractionEntity failed(UUID docId, ExtractionField field, String errorCode, int attempts) {
		return new PosFieldExtractionEntity(new PosFieldExtractionId(docId, field.name(), 3),
				ExtractionOutcome.FAILED, null, MODEL, null, attempts, errorCode, NOW);
	}

	// ---- upsert idempotency ----

	@Test
	void upsertIsIdempotentAndExistingRowWins() {
		Setup s = setupRecord("PROCESSING", List.of("documents/LAPPe.pdf"), List.of("PROCESSING"), null, null, null);
		UUID docId = s.documentIds().get(0);

		PosFieldExtractionEntity inserted = persistenceService.upsertExtractionOutcome(
				resolved(docId, ExtractionField.POLICYHOLDER_NAME, "First Value", 1));
		assertEquals("First Value", inserted.getValueText());

		// Second upsert with the SAME key but a different value: the existing row wins.
		PosFieldExtractionEntity existing = persistenceService.upsertExtractionOutcome(
				resolved(docId, ExtractionField.POLICYHOLDER_NAME, "Second Value", 2));
		assertEquals("First Value", existing.getValueText(), "existing row must not be overwritten");

		Integer count = jdbc.queryForObject(
				"SELECT COUNT(*) FROM pos_field_extraction WHERE document_id = ? AND field_name = ? "
						+ "AND prompt_version = 3",
				Integer.class, docId.toString(), "POLICYHOLDER_NAME");
		assertEquals(1, count);
	}

	@Test
	void upsertStoresAllThreeOutcomesForOneDocument() {
		Setup s = setupRecord("PROCESSING", List.of("documents/LAPPe.pdf"), List.of("PROCESSING"), null, null, null);
		UUID docId = s.documentIds().get(0);

		persistenceService.upsertExtractionOutcome(resolved(docId, ExtractionField.POLICYHOLDER_NAME, "Charlie Henry", 1));
		persistenceService.upsertExtractionOutcome(unknown(docId, ExtractionField.CONSULTANT_NAME, 3));
		persistenceService.upsertExtractionOutcome(failed(docId, ExtractionField.POLICY_CREATE_DATE, "OCR_TIMEOUT", 3));

		Integer count = jdbc.queryForObject(
				"SELECT COUNT(*) FROM pos_field_extraction WHERE document_id = ?", Integer.class,
				docId.toString());
		assertEquals(3, count);

		String policyholder = jdbc.queryForObject(
				"SELECT value_text FROM pos_field_extraction WHERE document_id = ? AND field_name = ?",
				String.class, docId.toString(), "POLICYHOLDER_NAME");
		assertEquals("Charlie Henry", policyholder);
	}

	// ---- applyResolvedField null-guard ----

	@Test
	void applyResolvedFieldUpdatesWhenNull() {
		Setup s = setupRecord("PROCESSING", List.of("documents/LAPPe.pdf"), List.of("PROCESSING"), null, null, null);

		persistenceService.applyResolvedField(s.recordId(), ExtractionField.POLICYHOLDER_NAME, "Charlie Henry");

		String value = jdbc.queryForObject(
				"SELECT policyholder_name FROM pos_record WHERE id = ?", String.class, s.recordId().toString());
		assertEquals("Charlie Henry", value);
	}

	@Test
	void applyResolvedFieldIsNoopWhenNonNull() {
		Setup s = setupRecord("PROCESSING", List.of("documents/LAPPe.pdf"), List.of("PROCESSING"), "Existing", null,
				null);

		persistenceService.applyResolvedField(s.recordId(), ExtractionField.POLICYHOLDER_NAME, "New Value");

		String value = jdbc.queryForObject(
				"SELECT policyholder_name FROM pos_record WHERE id = ?", String.class, s.recordId().toString());
		assertEquals("Existing", value, "a non-null field must not be overwritten");
	}

	@Test
	void applyResolvedFieldAppliesDate() {
		Setup s = setupRecord("PROCESSING", List.of("documents/LAPPe.pdf"), List.of("PROCESSING"), null, null, null);

		persistenceService.applyResolvedField(s.recordId(), ExtractionField.POLICY_CREATE_DATE, "2026-07-26");

		String value = jdbc.queryForObject(
				"SELECT policy_create_date FROM pos_record WHERE id = ?", String.class, s.recordId().toString());
		assertEquals("2026-07-26", value);
	}

	@Test
	void applyResolvedFieldBumpsTimestampAndVersionOnlyOnAnActualUpdate() throws Exception {
		Setup s = setupRecord("PROCESSING", List.of("documents/LAPPe.pdf"), List.of("PROCESSING"), null, null, null);
		Long beforeVersion = jdbc.queryForObject("SELECT version FROM pos_record WHERE id = ?", Long.class,
				s.recordId().toString());
		Long beforeUpdatedAt = jdbc.queryForObject("SELECT updated_at_epoch_ms FROM pos_record WHERE id = ?",
				Long.class, s.recordId().toString());
		Thread.sleep(10L); // ensure the stamp is observably later

		persistenceService.applyResolvedField(s.recordId(), ExtractionField.POLICYHOLDER_NAME, "Charlie Henry");

		Long afterVersion = jdbc.queryForObject("SELECT version FROM pos_record WHERE id = ?", Long.class,
				s.recordId().toString());
		Long afterUpdatedAt = jdbc.queryForObject("SELECT updated_at_epoch_ms FROM pos_record WHERE id = ?",
				Long.class, s.recordId().toString());
		assertEquals(beforeVersion + 1L, afterVersion, "an actual update must bump the version");
		assertTrue(afterUpdatedAt > beforeUpdatedAt, "an actual update must bump updated_at");
	}

	@Test
	void applyResolvedFieldNoopLeavesTimestampAndVersionUnchanged() {
		Setup s = setupRecord("PROCESSING", List.of("documents/LAPPe.pdf"), List.of("PROCESSING"), "Existing", null,
				null);
		Long beforeVersion = jdbc.queryForObject("SELECT version FROM pos_record WHERE id = ?", Long.class,
				s.recordId().toString());
		Long beforeUpdatedAt = jdbc.queryForObject("SELECT updated_at_epoch_ms FROM pos_record WHERE id = ?",
				Long.class, s.recordId().toString());

		persistenceService.applyResolvedField(s.recordId(), ExtractionField.POLICYHOLDER_NAME, "New Value");

		Long afterVersion = jdbc.queryForObject("SELECT version FROM pos_record WHERE id = ?", Long.class,
				s.recordId().toString());
		Long afterUpdatedAt = jdbc.queryForObject("SELECT updated_at_epoch_ms FROM pos_record WHERE id = ?",
				Long.class, s.recordId().toString());
		assertEquals(beforeVersion, afterVersion, "a no-op must not bump the version");
		assertEquals(beforeUpdatedAt, afterUpdatedAt, "a no-op must not change updated_at");
	}

	// ---- resolvedBusinessFields ----

	@Test
	void resolvedBusinessFieldsReflectsNonNullFields() {
		Setup s = setupRecord("PROCESSING", List.of("documents/LAPPe.pdf"), List.of("PROCESSING"), "Charlie", null,
				"2026-01-01");

		Set<ExtractionField> resolved = persistenceService.resolvedBusinessFields(s.recordId());
		assertTrue(resolved.contains(ExtractionField.POLICYHOLDER_NAME));
		assertTrue(resolved.contains(ExtractionField.POLICY_CREATE_DATE));
		org.junit.jupiter.api.Assertions.assertFalse(resolved.contains(ExtractionField.CONSULTANT_NAME));
		assertEquals(2, resolved.size());
	}

	// ---- completion gate ----

	@Test
	void completeWorkflowSucceedsWhenAllDocumentsTerminal() {
		// Candidate COMPLETED, non-candidate SKIPPED -> all terminal.
		Setup s = setupRecord("PROCESSING", List.of("documents/LAPPe.pdf", "documents/other.pdf"),
				List.of("COMPLETED", "SKIPPED"), "Charlie Henry", "John Davidson", "2026-07-26");

		persistenceService.completeWorkflow(s.jobId(), s.recordId(), NOW);

		String jobStatus = jdbc.queryForObject("SELECT status FROM ingestion_job WHERE id = ?", String.class,
				s.jobId().toString());
		assertEquals("COMPLETED", jobStatus);
		String recordStatus = jdbc.queryForObject("SELECT status FROM pos_record WHERE id = ?", String.class,
				s.recordId().toString());
		assertEquals("REVIEW_REQUIRED", recordStatus);
	}

	@Test
	void completeWorkflowAllowsNullBusinessFields() {
		Setup s = setupRecord("PROCESSING", List.of("documents/LAPPe.pdf"), List.of("COMPLETED"), null, null, null);

		// No exception despite all business fields being null.
		persistenceService.completeWorkflow(s.jobId(), s.recordId(), NOW);

		String recordStatus = jdbc.queryForObject("SELECT status FROM pos_record WHERE id = ?", String.class,
				s.recordId().toString());
		assertEquals("REVIEW_REQUIRED", recordStatus);
	}

	@Test
	void markDocumentFailedSetsTerminalFailedStatus() {
		Setup s = setupRecord("PROCESSING", List.of("documents/LAPPe.pdf"), List.of("PROCESSING"), null, null, null);
		UUID docId = s.documentIds().get(0);

		persistenceService.markDocumentFailed(docId);

		String status = jdbc.queryForObject("SELECT processing_status FROM pos_document WHERE id = ?", String.class,
				docId.toString());
		assertEquals("FAILED", status);
	}

	@Test
	void completeWorkflowSucceedsWhenCandidateFailedAndOtherSkipped() {
		// Candidate FAILED (permanent render failure), non-candidate SKIPPED -> all terminal.
		Setup s = setupRecord("PROCESSING", List.of("documents/LAPPe.pdf", "documents/other.pdf"),
				List.of("FAILED", "SKIPPED"), null, null, null);

		persistenceService.completeWorkflow(s.jobId(), s.recordId(), NOW);

		String jobStatus = jdbc.queryForObject("SELECT status FROM ingestion_job WHERE id = ?", String.class,
				s.jobId().toString());
		assertEquals("COMPLETED", jobStatus);
		String recordStatus = jdbc.queryForObject("SELECT status FROM pos_record WHERE id = ?", String.class,
				s.recordId().toString());
		assertEquals("REVIEW_REQUIRED", recordStatus);
	}

	@Test
	void completeWorkflowRefusesWhenADocumentIsPending() {
		Setup s = setupRecord("PROCESSING", List.of("documents/LAPPe.pdf"), List.of("PENDING"), null, null, null);

		ConsumerException ex = assertThrows(ConsumerException.class,
				() -> persistenceService.completeWorkflow(s.jobId(), s.recordId(), NOW));
		assertEquals(ConsumerException.Code.EXTRACTION_STATE_CONFLICT, ex.getCode());
	}

	@Test
	void completeWorkflowRefusesWhenADocumentIsProcessing() {
		Setup s = setupRecord("PROCESSING", List.of("documents/LAPPe.pdf"), List.of("PROCESSING"), null, null, null);

		ConsumerException ex = assertThrows(ConsumerException.class,
				() -> persistenceService.completeWorkflow(s.jobId(), s.recordId(), NOW));
		assertEquals(ConsumerException.Code.EXTRACTION_STATE_CONFLICT, ex.getCode());
	}

	@Test
	void completeWorkflowRefusesJobRecordMismatch() {
		Setup s = setupRecord("PROCESSING", List.of("documents/LAPPe.pdf"), List.of("COMPLETED"), null, null, null);
		// A real second record plus a job that belongs to it (not to s.recordId()).
		UUID otherRecordId = UUID.randomUUID();
		UUID otherJobId = UUID.randomUUID();
		UUID otherArchiveId = UUID.randomUUID();
		long now = System.currentTimeMillis();
		jdbc.update(
				"INSERT INTO storage_object (id, object_key, original_filename, content_type, byte_size, sha256, "
						+ "created_at_epoch_ms) VALUES (?,?,?,?,?,?,?)",
				otherArchiveId.toString(), "archives/" + otherRecordId + "/archive.zip", "archive.zip",
				"application/zip", 100, "a".repeat(64), now);
		jdbc.update(
				"INSERT INTO pos_record (id, source_archive_id, status, uploaded_by, uploaded_at_epoch_ms, "
						+ "updated_at_epoch_ms, version) VALUES (?,?,?,?,?,?,?)",
				otherRecordId.toString(), otherArchiveId.toString(), "PROCESSING", "test-user", now, now, 1);
		jdbc.update(
				"INSERT INTO ingestion_job (id, pos_record_id, status, attempt_count, created_at_epoch_ms, "
						+ "version) VALUES (?,?,?,?,?,?)",
				otherJobId.toString(), otherRecordId.toString(), "RUNNING", 1, now, 1);

		ConsumerException ex = assertThrows(ConsumerException.class,
				() -> persistenceService.completeWorkflow(otherJobId, s.recordId(), NOW));
		assertEquals(ConsumerException.Code.ID_MISMATCH, ex.getCode());
	}

	@Test
	void completeWorkflowRefusesWhenThereIsNoPdf() {
		// A record with no PDF documents (zero docs).
		Setup s = setupRecord("PROCESSING", List.of(), List.of(), null, null, null);

		ConsumerException ex = assertThrows(ConsumerException.class,
				() -> persistenceService.completeWorkflow(s.jobId(), s.recordId(), NOW));
		assertEquals(ConsumerException.Code.EXTRACTION_STATE_CONFLICT, ex.getCode());
	}

}
