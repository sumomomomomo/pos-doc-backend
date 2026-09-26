package horse.sumomo.pos_doc_backend.content;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;

import horse.sumomo.pos_doc_backend.persistence.entity.PosDocumentEntity;
import horse.sumomo.pos_doc_backend.persistence.entity.PosRecordEntity;
import horse.sumomo.pos_doc_backend.persistence.repository.PosDocumentRepository;
import horse.sumomo.pos_doc_backend.persistence.repository.PosRecordRepository;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SearchPageArchiveServiceTest {
	private final PosRecordRepository records = mock(PosRecordRepository.class);
	private final PosDocumentRepository documents = mock(PosDocumentRepository.class);
	private final DocumentContentService content = mock(DocumentContentService.class);
	private final SearchPageArchiveService service;

	SearchPageArchiveServiceTest() throws Exception {
		service = new SearchPageArchiveService(records, documents, content);
	}

	@Test
	void retainsOrderCreatesEmptyFolderAndDisambiguatesPdfNames() throws Exception {
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		UUID d1 = UUID.randomUUID();
		UUID d2 = UUID.randomUUID();
		UUID d3 = UUID.randomUUID();
		UUID d4 = UUID.randomUUID();
		PosRecordEntity r1 = mock(PosRecordEntity.class);
		PosRecordEntity r2 = mock(PosRecordEntity.class);
		when(r1.getErefNumber()).thenReturn("EREF-1");
		when(r2.getErefNumber()).thenReturn("EREF-2");
		when(records.findByIdAndDeletedAtIsNull(first)).thenReturn(Optional.of(r1));
		when(records.findByIdAndDeletedAtIsNull(second)).thenReturn(Optional.of(r2));
		PosDocumentEntity doc1 = mock(PosDocumentEntity.class);
		PosDocumentEntity doc2 = mock(PosDocumentEntity.class);
		PosDocumentEntity doc3 = mock(PosDocumentEntity.class);
		PosDocumentEntity doc4 = mock(PosDocumentEntity.class);
		when(doc1.getId()).thenReturn(d1);
		when(doc2.getId()).thenReturn(d2);
		when(doc3.getId()).thenReturn(d3);
		when(doc4.getId()).thenReturn(d4);
		when(documents.findByPosRecordIdOrderBySequenceNumberAsc(first)).thenReturn(List.of(doc1, doc2, doc3, doc4));
		when(documents.findByPosRecordIdOrderBySequenceNumberAsc(second)).thenReturn(List.of());
		when(content.pdfDescriptor(first, d1)).thenReturn(new ContentDescriptor("1", "secret1", "application/pdf", "../report.pdf", 4));
		when(content.pdfDescriptor(first, d2)).thenReturn(new ContentDescriptor("2", "secret2", "application/pdf", "../report.pdf", 4));
		when(content.pdfDescriptor(first, d3)).thenReturn(new ContentDescriptor("3", "secret3", "application/pdf", "scan", 4));
		when(content.pdfDescriptor(first, d4)).thenReturn(new ContentDescriptor("4", "secret4", "application/pdf", "CON", 4));
		doAnswer(inv -> { ((OutputStream) inv.getArgument(1)).write(new byte[] {1, 2, 3, 4}); return null; })
				.when(content).streamContent(any(), any());
		Path archive = service.create(List.of(first, second));
		try (ZipFile zip = new ZipFile(archive.toFile())) {
			assertEquals(List.of("EREF-1/", "EREF-1/__report.pdf", "EREF-1/__report-2.pdf", "EREF-1/scan.pdf", "EREF-1/_CON.pdf", "EREF-2/"),
					zip.stream().map(java.util.zip.ZipEntry::getName).toList());
			assertArrayEquals(new byte[] {1, 2, 3, 4}, zip.getInputStream(zip.getEntry("EREF-1/__report.pdf")).readAllBytes());
		}
		Files.delete(archive);
		assertFalse(Files.exists(archive));
	}

	@Test
	void rejectsInvalidAndUnavailableRecordsWithoutRetainingArchive() throws Exception {
		UUID id = UUID.randomUUID();
		assertEquals(SearchPageArchiveException.Code.INVALID_PAGE,
				assertThrows(SearchPageArchiveException.class, () -> service.create(List.of(id, id))).getCode());
		assertEquals(SearchPageArchiveException.Code.PAGE_CHANGED,
				assertThrows(SearchPageArchiveException.class, () -> service.create(List.of(id))).getCode());
		PosRecordEntity record = mock(PosRecordEntity.class);
		when(records.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(record));
		assertEquals(SearchPageArchiveException.Code.MISSING_EREF,
				assertThrows(SearchPageArchiveException.class, () -> service.create(List.of(id))).getCode());
		when(record.getErefNumber()).thenReturn("EREF-1");
		PosDocumentEntity document = mock(PosDocumentEntity.class);
		UUID documentId = UUID.randomUUID();
		when(document.getId()).thenReturn(documentId);
		when(documents.findByPosRecordIdOrderBySequenceNumberAsc(id)).thenReturn(List.of(document));
		when(content.pdfDescriptor(id, documentId))
				.thenThrow(new DocumentContentException(DocumentContentException.Code.DOCUMENT_NOT_FOUND));
		assertEquals(SearchPageArchiveException.Code.PAGE_CHANGED,
				assertThrows(SearchPageArchiveException.class, () -> service.create(List.of(id))).getCode());
		doReturn(new ContentDescriptor("1", "secret", "application/pdf", "scan.pdf", 4))
				.when(content).pdfDescriptor(id, documentId);
		doThrow(new DocumentContentException(DocumentContentException.Code.DOCUMENT_STORAGE_UNAVAILABLE))
				.when(content).streamContent(any(), any());
		assertThrows(DocumentContentException.class, () -> service.create(List.of(id)));
		doAnswer(inv -> { ((OutputStream) inv.getArgument(1)).write(new byte[] {1, 2}); return null; })
				.when(content).streamContent(any(), any());
		assertEquals(SearchPageArchiveException.Code.FAILED,
				assertThrows(SearchPageArchiveException.class, () -> service.create(List.of(id))).getCode());
		try (var files = Files.list(Path.of(System.getProperty("java.io.tmpdir"), "pos-search-page-archives"))) {
			assertTrue(files.noneMatch(p -> p.getFileName().toString().startsWith("page-")));
		}
	}

	@Test
	void startupRemovesOnlyArchivesFromStoppedProcesses() throws Exception {
		Path directory = Path.of(System.getProperty("java.io.tmpdir"), "pos-search-page-archives");
		Path stale = Files.createTempFile(directory, "page-9223372036854775807-", ".zip");
		Path active = Files.createTempFile(directory, "page-" + ProcessHandle.current().pid() + "-", ".zip");
		try {
			new SearchPageArchiveService(records, documents, content);
			assertFalse(Files.exists(stale));
			assertTrue(Files.exists(active));
		} finally {
			Files.deleteIfExists(stale);
			Files.deleteIfExists(active);
		}
	}
}
