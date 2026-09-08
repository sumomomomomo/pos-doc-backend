package horse.sumomo.pos_doc_backend.ingestion.consumer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import horse.sumomo.pos_doc_backend.persistence.model.DocumentProcessingStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link DocumentOcrPersistenceService#persistOcrResult}
 * exercising the native SQLite upsert and reconciliation behavior through
 * the service API (not raw database constraint tests).
 *
 * <p>Against a real temporary SQLite database, proves:
 * <ol>
 *   <li>Ordinary insertion succeeds and marks the document COMPLETED.</li>
 *   <li>Equivalent pre-existing result is idempotent success (no exception).</li>
 *   <li>Same-length but different OCR text is rejected with
 *       EXTRACTION_STATE_CONFLICT.</li>
 *   <li>Conflicting model is rejected with EXTRACTION_STATE_CONFLICT.</li>
 *   <li>Conflicting finish reason is rejected with EXTRACTION_STATE_CONFLICT.</li>
 *   <li>Existing row is never overwritten (original text preserved).</li>
 *   <li>Document status transitions are correct.</li>
 * </ol>
 */
@SpringBootTest(properties = {
		"app.messaging.outbox.enabled=false",
		"app.ingestion.consumer.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DocumentOcrPersistenceServiceIntegrationTest {

	private static final String MODEL = "/models/dotsmocr-1.8b-q8_0.gguf";
	private static final String FINISH_REASON = "stop";
	private static final Instant COMPLETED_AT = Instant.parse("2026-01-02T03:04:05Z");

	@Autowired
	private DocumentOcrPersistenceService persistenceService;

	@Autowired
	private JdbcTemplate jdbc;

	@DynamicPropertySource
	static void sqliteUrl(DynamicPropertyRegistry registry) throws Exception {
		Path dbFile = Files.createTempFile("pos-doc-ocr-persist-int-test", ".db");
		dbFile.toFile().deleteOnExit();
		registry.add("spring.datasource.url",
				() -> "jdbc:sqlite:" + dbFile.toAbsolutePath());
	}

	private UUID setupDocument(String status) {
		UUID recordId = UUID.randomUUID();
		UUID documentId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID archiveStorageId = UUID.randomUUID();
		UUID docStorageId = UUID.randomUUID();
		long now = System.currentTimeMillis();

		this.jdbc.update(
				"INSERT INTO storage_object (id, object_key, original_filename, content_type, "
						+ "byte_size, sha256, created_at_epoch_ms) VALUES (?,?,?,?,?,?,?)",
				archiveStorageId.toString(), "archives/" + recordId + "/archive.zip",
				"archive.zip", "application/zip", 100, "a".repeat(64), now);
		this.jdbc.update(
				"INSERT INTO storage_object (id, object_key, original_filename, content_type, "
						+ "byte_size, sha256, created_at_epoch_ms) VALUES (?,?,?,?,?,?,?)",
				docStorageId.toString(), "documents/" + recordId + "/" + documentId + ".pdf",
				"doc.pdf", "application/pdf", 100, "b".repeat(64), now);
		this.jdbc.update(
				"INSERT INTO pos_record (id, source_archive_id, status, uploaded_by, "
						+ "uploaded_at_epoch_ms, updated_at_epoch_ms, version) VALUES (?,?,?,?,?,?,?)",
				recordId.toString(), archiveStorageId.toString(), "REVIEW_REQUIRED",
				"test-user", now, now, 1);
		this.jdbc.update(
				"INSERT INTO pos_document (id, pos_record_id, storage_object_id, "
						+ "sequence_number, document_type, processing_status) "
						+ "VALUES (?,?,?,?,?,?)",
				documentId.toString(), recordId.toString(), docStorageId.toString(),
				0, "OTHER", status);
		this.jdbc.update(
				"INSERT INTO ingestion_job (id, pos_record_id, status, attempt_count, "
						+ "created_at_epoch_ms, version) VALUES (?,?,?,?,?,?)",
				jobId.toString(), recordId.toString(), "QUEUED", 0, now, 1);
		return documentId;
	}

	@Test
	void ordinaryInsertionSucceedsAndMarksDocumentCompleted() {
		UUID documentId = setupDocument("PROCESSING");

		this.persistenceService.persistOcrResult(documentId, 1, "hello world", MODEL,
				FINISH_REASON, COMPLETED_AT);

		Integer count = this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM document_ocr_result WHERE document_id = ? AND prompt_version = 1",
				Integer.class, documentId.toString());
		assertEquals(1, count);

		String status = this.jdbc.queryForObject(
				"SELECT processing_status FROM pos_document WHERE id = ?",
				String.class, documentId.toString());
		assertEquals("COMPLETED", status);
	}

	@Test
	void equivalentPreExistingResultIsIdempotentSuccess() {
		UUID documentId = setupDocument("PROCESSING");

		this.persistenceService.persistOcrResult(documentId, 1, "same text", MODEL,
				FINISH_REASON, COMPLETED_AT);

		// Second call with identical values: no exception.
		this.persistenceService.persistOcrResult(documentId, 1, "same text", MODEL,
				FINISH_REASON, COMPLETED_AT);

		Integer count = this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM document_ocr_result WHERE document_id = ? AND prompt_version = 1",
				Integer.class, documentId.toString());
		assertEquals(1, count);
	}

	@Test
	void sameLengthDifferentOcrTextIsRejected() {
		UUID documentId = setupDocument("PROCESSING");

		this.persistenceService.persistOcrResult(documentId, 1, "original", MODEL,
				FINISH_REASON, COMPLETED_AT);

		ConsumerException ex = assertThrows(ConsumerException.class,
				() -> this.persistenceService.persistOcrResult(documentId, 1, "modified", MODEL,
						FINISH_REASON, COMPLETED_AT));
		assertEquals(ConsumerException.Code.EXTRACTION_STATE_CONFLICT, ex.getCode());
	}

	@Test
	void conflictingModelIsRejected() {
		UUID documentId = setupDocument("PROCESSING");

		this.persistenceService.persistOcrResult(documentId, 1, "text", MODEL,
				FINISH_REASON, COMPLETED_AT);

		ConsumerException ex = assertThrows(ConsumerException.class,
				() -> this.persistenceService.persistOcrResult(documentId, 1, "text",
						"/models/other-model.gguf", FINISH_REASON, COMPLETED_AT));
		assertEquals(ConsumerException.Code.EXTRACTION_STATE_CONFLICT, ex.getCode());
	}

	@Test
	void conflictingFinishReasonIsRejected() {
		UUID documentId = setupDocument("PROCESSING");

		this.persistenceService.persistOcrResult(documentId, 1, "text", MODEL,
				FINISH_REASON, COMPLETED_AT);

		ConsumerException ex = assertThrows(ConsumerException.class,
				() -> this.persistenceService.persistOcrResult(documentId, 1, "text", MODEL,
						"length", COMPLETED_AT));
		assertEquals(ConsumerException.Code.EXTRACTION_STATE_CONFLICT, ex.getCode());
	}

	@Test
	void existingRowIsNeverOverwritten() {
		UUID documentId = setupDocument("PROCESSING");

		this.persistenceService.persistOcrResult(documentId, 1, "original text", MODEL,
				FINISH_REASON, COMPLETED_AT);

		// Attempt to overwrite with different text (same length to isolate
		// the text comparison).
		assertThrows(ConsumerException.class,
				() -> this.persistenceService.persistOcrResult(documentId, 1, "changed text", MODEL,
						FINISH_REASON, COMPLETED_AT));

		// Original text is preserved.
		String storedText = this.jdbc.queryForObject(
				"SELECT ocr_text FROM document_ocr_result WHERE document_id = ? AND prompt_version = 1",
				String.class, documentId.toString());
		assertEquals("original text", storedText);
	}

	@Test
	void documentStatusIsCompletedAfterSuccessfulPersist() {
		UUID documentId = setupDocument("PROCESSING");

		this.persistenceService.persistOcrResult(documentId, 1, "text", MODEL,
				FINISH_REASON, COMPLETED_AT);

		String status = this.jdbc.queryForObject(
				"SELECT processing_status FROM pos_document WHERE id = ?",
				String.class, documentId.toString());
		assertEquals(DocumentProcessingStatus.COMPLETED.name(), status);
	}

}
