package horse.sumomo.pos_doc_backend.ingestion.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import horse.sumomo.pos_doc_backend.ocr.application.FirstPageOcrService;
import horse.sumomo.pos_doc_backend.ocr.model.OcrResult;
import horse.sumomo.pos_doc_backend.ocr.service.OcrException;
import horse.sumomo.pos_doc_backend.persistence.entity.PosDocumentEntity;
import horse.sumomo.pos_doc_backend.persistence.entity.PosRecordEntity;
import horse.sumomo.pos_doc_backend.persistence.entity.StorageObjectEntity;
import horse.sumomo.pos_doc_backend.persistence.model.DocumentProcessingStatus;
import horse.sumomo.pos_doc_backend.persistence.model.DocumentType;
import horse.sumomo.pos_doc_backend.rendering.service.RenderingException;

/**
 * Workflow unit tests for {@link DocumentOcrWorkflowService} with mocked
 * boundaries and real domain objects where practical.
 */
class DocumentOcrWorkflowServiceTest {

	private static final String OCR_TEXT = "synthetic-ocr-text";
	private static final String MODEL = "/models/dotsmocr-1.8b-q8_0.gguf";
	private static final String FINISH_REASON = "stop";

	private DocumentOcrPersistenceService persistenceService;
	private FirstPageOcrService ocrService;
	private DocumentOcrWorkflowService workflowService;

	@BeforeEach
	void setUp() {
		this.persistenceService = mock(DocumentOcrPersistenceService.class);
		this.ocrService = mock(FirstPageOcrService.class);
		this.workflowService = new DocumentOcrWorkflowService(this.persistenceService, this.ocrService);
	}

	@Test
	void documentsAreProcessedInSequenceOrder() {
		UUID posRecordId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID doc1Id = UUID.randomUUID();
		UUID doc2Id = UUID.randomUUID();

		PosDocumentEntity doc1 = createDocument(doc1Id, posRecordId, 0);
		PosDocumentEntity doc2 = createDocument(doc2Id, posRecordId, 1);

		when(this.persistenceService.loadDocumentsInSequenceOrder(posRecordId)).thenReturn(List.of(doc1, doc2));
		when(this.persistenceService.inspectAndPrepare(doc1Id, 1))
				.thenReturn(DocumentOcrPersistenceService.DocumentOcrState.READY_FOR_OCR);
		when(this.persistenceService.inspectAndPrepare(doc2Id, 1))
				.thenReturn(DocumentOcrPersistenceService.DocumentOcrState.READY_FOR_OCR);
		when(this.ocrService.recognize(doc1Id)).thenReturn(createOcrResult(doc1Id));
		when(this.ocrService.recognize(doc2Id)).thenReturn(createOcrResult(doc2Id));

		this.workflowService.runOcrWorkflow(posRecordId, jobId);

		InOrder inOrder = inOrder(this.persistenceService, this.ocrService);
		inOrder.verify(this.persistenceService).inspectAndPrepare(doc1Id, 1);
		inOrder.verify(this.ocrService).recognize(doc1Id);
		inOrder.verify(this.persistenceService).persistOcrResult(eq(doc1Id), eq(1), eq(OCR_TEXT), eq(MODEL),
				eq(FINISH_REASON), any(Instant.class));
		inOrder.verify(this.persistenceService).inspectAndPrepare(doc2Id, 1);
		inOrder.verify(this.ocrService).recognize(doc2Id);
		inOrder.verify(this.persistenceService).persistOcrResult(eq(doc2Id), eq(1), eq(OCR_TEXT), eq(MODEL),
				eq(FINISH_REASON), any(Instant.class));
	}

	@Test
	void everyIncompleteDocumentIsPassedOnceToOcrService() {
		UUID posRecordId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID doc1Id = UUID.randomUUID();
		UUID doc2Id = UUID.randomUUID();

		PosDocumentEntity doc1 = createDocument(doc1Id, posRecordId, 0);
		PosDocumentEntity doc2 = createDocument(doc2Id, posRecordId, 1);

		when(this.persistenceService.loadDocumentsInSequenceOrder(posRecordId)).thenReturn(List.of(doc1, doc2));
		when(this.persistenceService.inspectAndPrepare(doc1Id, 1))
				.thenReturn(DocumentOcrPersistenceService.DocumentOcrState.READY_FOR_OCR);
		when(this.persistenceService.inspectAndPrepare(doc2Id, 1))
				.thenReturn(DocumentOcrPersistenceService.DocumentOcrState.READY_FOR_OCR);
		when(this.ocrService.recognize(doc1Id)).thenReturn(createOcrResult(doc1Id));
		when(this.ocrService.recognize(doc2Id)).thenReturn(createOcrResult(doc2Id));

		this.workflowService.runOcrWorkflow(posRecordId, jobId);

		verify(this.ocrService, times(1)).recognize(doc1Id);
		verify(this.ocrService, times(1)).recognize(doc2Id);
	}

	@Test
	void existingVersion1ResultSkipsOcr() {
		UUID posRecordId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID doc1Id = UUID.randomUUID();

		PosDocumentEntity doc1 = createDocument(doc1Id, posRecordId, 0);

		when(this.persistenceService.loadDocumentsInSequenceOrder(posRecordId)).thenReturn(List.of(doc1));
		when(this.persistenceService.inspectAndPrepare(doc1Id, 1))
				.thenReturn(DocumentOcrPersistenceService.DocumentOcrState.ALREADY_COMPLETED);

		this.workflowService.runOcrWorkflow(posRecordId, jobId);

		verify(this.ocrService, never()).recognize(any());
	}

	@Test
	void version2ResultDoesNotCauseVersion1ToBeSkipped() {
		UUID posRecordId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID doc1Id = UUID.randomUUID();

		PosDocumentEntity doc1 = createDocument(doc1Id, posRecordId, 0);

		// The persistence service returns READY_FOR_OCR, meaning no
		// version-1 result exists (a version-2 result does not count).
		when(this.persistenceService.loadDocumentsInSequenceOrder(posRecordId)).thenReturn(List.of(doc1));
		when(this.persistenceService.inspectAndPrepare(doc1Id, 1))
				.thenReturn(DocumentOcrPersistenceService.DocumentOcrState.READY_FOR_OCR);
		when(this.ocrService.recognize(doc1Id)).thenReturn(createOcrResult(doc1Id));

		this.workflowService.runOcrWorkflow(posRecordId, jobId);

		verify(this.ocrService, times(1)).recognize(doc1Id);
	}

	@Test
	void returnedDocumentIdAndPromptVersionMustMatch() {
		UUID posRecordId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID doc1Id = UUID.randomUUID();
		UUID wrongDocId = UUID.randomUUID();

		PosDocumentEntity doc1 = createDocument(doc1Id, posRecordId, 0);

		when(this.persistenceService.loadDocumentsInSequenceOrder(posRecordId)).thenReturn(List.of(doc1));
		when(this.persistenceService.inspectAndPrepare(doc1Id, 1))
				.thenReturn(DocumentOcrPersistenceService.DocumentOcrState.READY_FOR_OCR);
		// Return a result with the wrong document ID.
		when(this.ocrService.recognize(doc1Id)).thenReturn(createOcrResult(wrongDocId));

		ConsumerException e = assertThrows(ConsumerException.class,
				() -> this.workflowService.runOcrWorkflow(posRecordId, jobId));
		assertEquals(ConsumerException.Code.EXTRACTION_STATE_CONFLICT, e.getCode());
	}

	@Test
	void ocrIsInvokedWithNoActiveTransaction() {
		UUID posRecordId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID doc1Id = UUID.randomUUID();

		PosDocumentEntity doc1 = createDocument(doc1Id, posRecordId, 0);

		when(this.persistenceService.loadDocumentsInSequenceOrder(posRecordId)).thenReturn(List.of(doc1));
		when(this.persistenceService.inspectAndPrepare(doc1Id, 1))
				.thenReturn(DocumentOcrPersistenceService.DocumentOcrState.READY_FOR_OCR);
		when(this.ocrService.recognize(doc1Id)).thenAnswer(invocation -> {
			// Assert no active transaction when OCR is called.
			assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
					"OCR must be invoked with no active transaction");
			return createOcrResult(doc1Id);
		});

		this.workflowService.runOcrWorkflow(posRecordId, jobId);
	}

	@Test
	void resultPersistenceOccursInTransactionSeparateFromOcr() {
		UUID posRecordId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID doc1Id = UUID.randomUUID();

		PosDocumentEntity doc1 = createDocument(doc1Id, posRecordId, 0);

		when(this.persistenceService.loadDocumentsInSequenceOrder(posRecordId)).thenReturn(List.of(doc1));
		when(this.persistenceService.inspectAndPrepare(doc1Id, 1))
				.thenReturn(DocumentOcrPersistenceService.DocumentOcrState.READY_FOR_OCR);
		when(this.ocrService.recognize(doc1Id)).thenReturn(createOcrResult(doc1Id));

		this.workflowService.runOcrWorkflow(posRecordId, jobId);

		// The OCR call and the persistence call are separate invocations.
		InOrder inOrder = inOrder(this.ocrService, this.persistenceService);
		inOrder.verify(this.ocrService).recognize(doc1Id);
		inOrder.verify(this.persistenceService).persistOcrResult(eq(doc1Id), eq(1), eq(OCR_TEXT), eq(MODEL),
				eq(FINISH_REASON), any(Instant.class));
	}

	@Test
	void completedEarlierDocumentsRemainCompletedWhenLaterDocumentFails() {
		UUID posRecordId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID doc1Id = UUID.randomUUID();
		UUID doc2Id = UUID.randomUUID();

		PosDocumentEntity doc1 = createDocument(doc1Id, posRecordId, 0);
		PosDocumentEntity doc2 = createDocument(doc2Id, posRecordId, 1);

		when(this.persistenceService.loadDocumentsInSequenceOrder(posRecordId)).thenReturn(List.of(doc1, doc2));
		when(this.persistenceService.inspectAndPrepare(doc1Id, 1))
				.thenReturn(DocumentOcrPersistenceService.DocumentOcrState.READY_FOR_OCR);
		when(this.persistenceService.inspectAndPrepare(doc2Id, 1))
				.thenReturn(DocumentOcrPersistenceService.DocumentOcrState.READY_FOR_OCR);
		when(this.ocrService.recognize(doc1Id)).thenReturn(createOcrResult(doc1Id));
		// Second document fails with a retryable OCR error.
		when(this.ocrService.recognize(doc2Id)).thenThrow(new OcrException(OcrException.Code.OCR_TIMEOUT));

		assertThrows(ConsumerException.class, () -> this.workflowService.runOcrWorkflow(posRecordId, jobId));

		// First document was persisted successfully.
		verify(this.persistenceService).persistOcrResult(eq(doc1Id), eq(1), eq(OCR_TEXT), eq(MODEL), eq(FINISH_REASON),
				any(Instant.class));
		// Second document was returned to PENDING.
		verify(this.persistenceService).markDocumentPending(doc2Id);
	}

	@Test
	void staleProcessingDocumentWithoutResultIsProcessedAgain() {
		UUID posRecordId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID doc1Id = UUID.randomUUID();

		PosDocumentEntity doc1 = createDocument(doc1Id, posRecordId, 0);

		when(this.persistenceService.loadDocumentsInSequenceOrder(posRecordId)).thenReturn(List.of(doc1));
		// The persistence service returns READY_FOR_OCR for a stale
		// PROCESSING document without a result.
		when(this.persistenceService.inspectAndPrepare(doc1Id, 1))
				.thenReturn(DocumentOcrPersistenceService.DocumentOcrState.READY_FOR_OCR);
		when(this.ocrService.recognize(doc1Id)).thenReturn(createOcrResult(doc1Id));

		this.workflowService.runOcrWorkflow(posRecordId, jobId);

		verify(this.ocrService, times(1)).recognize(doc1Id);
	}

	@Test
	void resultStatusMismatchIsRepaired() {
		UUID posRecordId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID doc1Id = UUID.randomUUID();

		PosDocumentEntity doc1 = createDocument(doc1Id, posRecordId, 0);

		when(this.persistenceService.loadDocumentsInSequenceOrder(posRecordId)).thenReturn(List.of(doc1));
		// The persistence service returns STATUS_REPAIRED_COMPLETED,
		// meaning a result exists but the status was not COMPLETED.
		when(this.persistenceService.inspectAndPrepare(doc1Id, 1))
				.thenReturn(DocumentOcrPersistenceService.DocumentOcrState.STATUS_REPAIRED_COMPLETED);

		this.workflowService.runOcrWorkflow(posRecordId, jobId);

		// OCR is not called because the result already exists.
		verify(this.ocrService, never()).recognize(any());
	}

	@Test
	void noOcrTextAppearsInExceptionMessages() {
		UUID posRecordId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID doc1Id = UUID.randomUUID();

		PosDocumentEntity doc1 = createDocument(doc1Id, posRecordId, 0);

		when(this.persistenceService.loadDocumentsInSequenceOrder(posRecordId)).thenReturn(List.of(doc1));
		when(this.persistenceService.inspectAndPrepare(doc1Id, 1))
				.thenReturn(DocumentOcrPersistenceService.DocumentOcrState.READY_FOR_OCR);
		when(this.ocrService.recognize(doc1Id)).thenThrow(new OcrException(OcrException.Code.OCR_TIMEOUT));

		ConsumerException e = assertThrows(ConsumerException.class,
				() -> this.workflowService.runOcrWorkflow(posRecordId, jobId));
		assertFalse(e.getMessage().contains(OCR_TEXT), "exception must not contain OCR text");
	}

	@Test
	void retryableRenderingFailureReturnsDocumentToPending() {
		UUID posRecordId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID doc1Id = UUID.randomUUID();

		PosDocumentEntity doc1 = createDocument(doc1Id, posRecordId, 0);

		when(this.persistenceService.loadDocumentsInSequenceOrder(posRecordId)).thenReturn(List.of(doc1));
		when(this.persistenceService.inspectAndPrepare(doc1Id, 1))
				.thenReturn(DocumentOcrPersistenceService.DocumentOcrState.READY_FOR_OCR);
		when(this.ocrService.recognize(doc1Id))
				.thenThrow(new RenderingException(RenderingException.Code.PDF_STORAGE_UNAVAILABLE));

		ConsumerException e = assertThrows(ConsumerException.class,
				() -> this.workflowService.runOcrWorkflow(posRecordId, jobId));
		assertTrue(e.getCode().retryable());
		verify(this.persistenceService).markDocumentPending(doc1Id);
	}

	@Test
	void nonRetryableRenderingFailureMarksDocumentFailed() {
		UUID posRecordId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		UUID doc1Id = UUID.randomUUID();

		PosDocumentEntity doc1 = createDocument(doc1Id, posRecordId, 0);

		when(this.persistenceService.loadDocumentsInSequenceOrder(posRecordId)).thenReturn(List.of(doc1));
		when(this.persistenceService.inspectAndPrepare(doc1Id, 1))
				.thenReturn(DocumentOcrPersistenceService.DocumentOcrState.READY_FOR_OCR);
		when(this.ocrService.recognize(doc1Id))
				.thenThrow(new RenderingException(RenderingException.Code.PDF_INVALID));

		ConsumerException e = assertThrows(ConsumerException.class,
				() -> this.workflowService.runOcrWorkflow(posRecordId, jobId));
		assertFalse(e.getCode().retryable());
		verify(this.persistenceService).markDocumentFailed(doc1Id);
	}

	@Test
	void isRenderingRetryableCoversAllCodes() {
		// Retryable codes.
		assertTrue(DocumentOcrWorkflowService.isRenderingRetryable(RenderingException.Code.PDF_STORAGE_UNAVAILABLE));
		assertTrue(DocumentOcrWorkflowService.isRenderingRetryable(RenderingException.Code.RENDER_INTERRUPTED));
		assertTrue(DocumentOcrWorkflowService.isRenderingRetryable(RenderingException.Code.TEMP_STORAGE_UNAVAILABLE));

		// Terminal codes.
		assertFalse(DocumentOcrWorkflowService.isRenderingRetryable(RenderingException.Code.DOCUMENT_NOT_FOUND));
		assertFalse(DocumentOcrWorkflowService.isRenderingRetryable(RenderingException.Code.DOCUMENT_DELETED));
		assertFalse(DocumentOcrWorkflowService.isRenderingRetryable(RenderingException.Code.PDF_METADATA_INVALID));
		assertFalse(DocumentOcrWorkflowService.isRenderingRetryable(RenderingException.Code.PDF_OBJECT_MISSING));
		assertFalse(DocumentOcrWorkflowService.isRenderingRetryable(RenderingException.Code.PDF_SIZE_MISMATCH));
		assertFalse(DocumentOcrWorkflowService.isRenderingRetryable(RenderingException.Code.PDF_HASH_MISMATCH));
		assertFalse(DocumentOcrWorkflowService.isRenderingRetryable(RenderingException.Code.PDF_INVALID));
		assertFalse(DocumentOcrWorkflowService.isRenderingRetryable(RenderingException.Code.PAGE_DIMENSIONS_INVALID));
		assertFalse(DocumentOcrWorkflowService.isRenderingRetryable(RenderingException.Code.RENDER_LIMIT_EXCEEDED));
		assertFalse(DocumentOcrWorkflowService.isRenderingRetryable(RenderingException.Code.RENDER_FAILED));
	}

	private PosDocumentEntity createDocument(UUID documentId, UUID posRecordId, long sequenceNumber) {
		PosRecordEntity record = mock(PosRecordEntity.class);
		when(record.getId()).thenReturn(posRecordId);
		StorageObjectEntity storage = mock(StorageObjectEntity.class);
		return new PosDocumentEntity(documentId, record, storage, sequenceNumber, DocumentType.UNKNOWN,
				DocumentProcessingStatus.PENDING);
	}

	private OcrResult createOcrResult(UUID documentId) {
		return new OcrResult(documentId, OCR_TEXT, MODEL, FINISH_REASON, 1);
	}

}
