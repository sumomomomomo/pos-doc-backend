package horse.sumomo.pos_doc_backend.ingestion.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DocumentCandidateSelector}. Pure logic, no I/O.
 */
class DocumentCandidateSelectorTest {

	private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID B = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final UUID C = UUID.fromString("33333333-3333-3333-3333-333333333333");

	private static DocumentSnapshot snap(int seq, String filename) {
		return new DocumentSnapshot(UUID.randomUUID(), seq, filename);
	}

	private static UUID id(DocumentSnapshot s) {
		return s.documentId();
	}

	@Test
	void emptyListReturnsEmpty() {
		assertEquals(List.of(), DocumentCandidateSelector.select(List.of()));
	}

	@Test
	void nullListReturnsEmpty() {
		assertEquals(List.of(), DocumentCandidateSelector.select(null));
	}

	@Test
	void caseSensitiveMatchSelectsLappePdf() {
		// Only the exact "LAPPe.pdf" basename matches (case-sensitive).
		DocumentSnapshot lappe = new DocumentSnapshot(A, 0, "documents/LAPPe.pdf");
		DocumentSnapshot other = new DocumentSnapshot(B, 1, "documents/other.pdf");
		List<DocumentSnapshot> result = DocumentCandidateSelector.select(List.of(other, lappe));
		assertEquals(List.of(lappe), result);
	}

	@Test
	void wrongCaseDoesNotMatchAndFallsBack() {
		// "lappe.pdf" / "LAPPE.pdf" do NOT match case-sensitively, so the selector
		// falls back to the first (up to) ten PDFs.
		DocumentSnapshot lower = new DocumentSnapshot(A, 0, "documents/lappe.pdf");
		DocumentSnapshot upper = new DocumentSnapshot(B, 1, "documents/LAPPE.pdf");
		DocumentSnapshot mixed = new DocumentSnapshot(C, 2, "documents/LaPpE.pdf");
		List<DocumentSnapshot> result = DocumentCandidateSelector.select(List.of(lower, upper, mixed));
		// No case-sensitive match -> first up to 10, in sequence order.
		assertEquals(List.of(lower, upper, mixed), result);
	}

	@Test
	void everyMatchingLappeIsSelectedInSequenceOrder() {
		// Two case-sensitive matches: both are returned, in sequence order.
		DocumentSnapshot first = new DocumentSnapshot(A, 0, "documents/alphaLAPPe.pdf");
		DocumentSnapshot second = new DocumentSnapshot(B, 1, "documents/betaLAPPe.pdf");
		DocumentSnapshot other = new DocumentSnapshot(C, 2, "documents/other.pdf");
		List<DocumentSnapshot> result = DocumentCandidateSelector.select(List.of(other, second, first));
		assertEquals(List.of(first, second), result);
	}

	@Test
	void noMatchFallsBackToFirstTenInSequenceOrder() {
		List<DocumentSnapshot> pdfs = new java.util.ArrayList<>();
		for (int i = 0; i < 12; i++) {
			pdfs.add(snap(i, "documents/f" + i + ".pdf"));
		}
		List<DocumentSnapshot> result = DocumentCandidateSelector.select(pdfs);
		// 12 PDFs, no LAPPe -> the first 10 in sequence order.
		assertEquals(10, result.size());
		assertEquals(0, result.get(0).sequenceNumber());
		assertEquals(9, result.get(9).sequenceNumber());
	}

	@Test
	void noMatchWithFewerThanTenSelectsAll() {
		DocumentSnapshot a = snap(0, "documents/a.pdf");
		DocumentSnapshot b = snap(1, "documents/b.pdf");
		DocumentSnapshot c = snap(2, "documents/c.pdf");
		List<DocumentSnapshot> result = DocumentCandidateSelector.select(List.of(c, a, b));
		// 3 PDFs, no LAPPe -> all 3, in sequence order.
		assertEquals(List.of(a, b, c), result);
	}

	@Test
	void exactlyTenWithoutLappeSelectsAllTen() {
		List<DocumentSnapshot> pdfs = new java.util.ArrayList<>();
		for (int i = 0; i < 10; i++) {
			pdfs.add(snap(i, "documents/f" + i + ".pdf"));
		}
		assertEquals(10, DocumentCandidateSelector.select(pdfs).size());
	}

	@Test
	void matchUsesBasenameNotFullPath() {
		DocumentSnapshot deep = new DocumentSnapshot(A, 0, "dir/documents/LAPPe.pdf");
		DocumentSnapshot other = new DocumentSnapshot(B, 1, "documents/other.pdf");
		List<DocumentSnapshot> result = DocumentCandidateSelector.select(List.of(deep, other));
		assertEquals(List.of(deep), result);
	}

	@Test
	void mustEndInLappePdfNotJustContainIt() {
		// "LAPPe.pdf.txt" contains but does not END in "LAPPe.pdf" -> no match ->
		// fallback (both selected).
		DocumentSnapshot txt = new DocumentSnapshot(A, 0, "documents/LAPPe.pdf.txt");
		DocumentSnapshot other = new DocumentSnapshot(B, 1, "documents/other.pdf");
		List<DocumentSnapshot> result = DocumentCandidateSelector.select(List.of(txt, other));
		assertEquals(List.of(txt, other), result);
	}

	@Test
	void nullFilenameIsNotTreatedAsLappe() {
		DocumentSnapshot nullName = new DocumentSnapshot(A, 0, null);
		DocumentSnapshot other = new DocumentSnapshot(B, 1, "documents/other.pdf");
		List<DocumentSnapshot> result = DocumentCandidateSelector.select(List.of(nullName, other));
		// No case-sensitive match -> fallback (both, in sequence order).
		assertEquals(List.of(nullName, other), result);
	}

	@Test
	void singlePdfIsTheOnlyCandidate() {
		DocumentSnapshot only = new DocumentSnapshot(A, 0, "documents/whatever.pdf");
		List<DocumentSnapshot> result = DocumentCandidateSelector.select(List.of(only));
		assertEquals(List.of(only), result);
		assertEquals(A, id(result.get(0)));
	}

}
