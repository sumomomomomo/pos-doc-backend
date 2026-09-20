package horse.sumomo.pos_doc_backend.ingestion.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DocumentCandidateSelector}. Pure logic, no I/O.
 */
class DocumentCandidateSelectorTest {

	private static final UUID DOC_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID DOC_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final UUID DOC_C = UUID.fromString("33333333-3333-3333-3333-333333333333");

	private static DocumentSnapshot snap(int seq, String filename) {
		return new DocumentSnapshot(UUID.randomUUID(), seq, filename);
	}

	@Test
	void emptyListSelectsNone() {
		assertFalse(DocumentCandidateSelector.select(List.of()).isPresent());
	}

	@Test
	void nullListSelectsNone() {
		assertFalse(DocumentCandidateSelector.select(null).isPresent());
	}

	@Test
	void singlePdfIsTheCandidateRegardlessOfName() {
		// Even without a LAPPe.pdf name, a single PDF is the candidate.
		DocumentSnapshot only = new DocumentSnapshot(DOC_A, 0, "documents/whatever.pdf");
		Optional<UUID> result = DocumentCandidateSelector.select(List.of(only));
		assertTrue(result.isPresent());
		assertEquals(DOC_A, result.orElseThrow());
	}

	@Test
	void singlePdfCandidateIsThatPdf() {
		DocumentSnapshot only = new DocumentSnapshot(DOC_A, 0, "documents/whatever.pdf");
		Optional<UUID> result = DocumentCandidateSelector.select(List.of(only));
		assertEquals(DOC_A, result.orElseThrow());
	}

	@Test
	void twoToTenPicksFirstLappeInSequenceOrder() {
		// DOC_A (seq 0) is not LAPPe, DOC_B (seq 1) is LAPPe -> DOC_B wins.
		DocumentSnapshot a = new DocumentSnapshot(DOC_A, 0, "documents/other.pdf");
		DocumentSnapshot b = new DocumentSnapshot(DOC_B, 1, "documents/LAPPe.pdf");
		Optional<UUID> result = DocumentCandidateSelector.select(List.of(a, b));
		assertEquals(DOC_B, result.orElseThrow());
	}

	@Test
	void firstLappeInSequenceOrderWinsWhenMultipleMatch() {
		// Two LAPPe.pdf matches: the lower sequence number wins.
		DocumentSnapshot a = new DocumentSnapshot(DOC_A, 0, "documents/alphaLAPPe.pdf");
		DocumentSnapshot b = new DocumentSnapshot(DOC_B, 1, "documents/betaLAPPe.pdf");
		Optional<UUID> result = DocumentCandidateSelector.select(List.of(b, a)); // out of order input
		assertEquals(DOC_A, result.orElseThrow());
	}

	@Test
	void matchingIsCaseInsensitive() {
		DocumentSnapshot a = new DocumentSnapshot(DOC_A, 0, "documents/lappe.pdf");
		DocumentSnapshot b = new DocumentSnapshot(DOC_B, 1, "documents/OTHER.pdf");
		Optional<UUID> result = DocumentCandidateSelector.select(List.of(a, b));
		assertEquals(DOC_A, result.orElseThrow());
	}

	@Test
	void matchingUsesBasenameNotFullPath() {
		// The match is on the basename (after the last '/'), not a substring of the path.
		DocumentSnapshot a = new DocumentSnapshot(DOC_A, 0, "dir/documents/LAPPe.pdf");
		Optional<UUID> result = DocumentCandidateSelector.select(List.of(a,
				new DocumentSnapshot(DOC_B, 1, "documents/other.pdf")));
		assertEquals(DOC_A, result.orElseThrow());
	}

	@Test
	void mustEndInLappePdfNotJustContainIt() {
		// A file that contains "lappe.pdf" but does not END in it is not a match.
		DocumentSnapshot a = new DocumentSnapshot(DOC_A, 0, "documents/LAPPe.pdf.txt");
		DocumentSnapshot b = new DocumentSnapshot(DOC_B, 1, "documents/other.pdf");
		Optional<UUID> result = DocumentCandidateSelector.select(List.of(a, b));
		assertFalse(result.isPresent());
	}

	@Test
	void twoToTenWithoutLappeSelectsNone() {
		Optional<UUID> result = DocumentCandidateSelector.select(List.of(
				snap(0, "documents/a.pdf"),
				snap(1, "documents/b.pdf"),
				snap(2, "documents/c.pdf")));
		assertFalse(result.isPresent());
	}

	@Test
	void exactlyTenWithoutLappeSelectsNone() {
		List<DocumentSnapshot> pdfs = new java.util.ArrayList<>();
		for (int i = 0; i < 10; i++) {
			pdfs.add(snap(i, "documents/f" + i + ".pdf"));
		}
		assertFalse(DocumentCandidateSelector.select(pdfs).isPresent());
	}

	@Test
	void exactlyTenWithLappePicksLappe() {
		List<DocumentSnapshot> pdfs = new java.util.ArrayList<>();
		for (int i = 0; i < 10; i++) {
			pdfs.add(new DocumentSnapshot(UUID.randomUUID(), i,
					i == 7 ? "documents/theLAPPe.pdf" : "documents/f" + i + ".pdf"));
		}
		Optional<UUID> result = DocumentCandidateSelector.select(pdfs);
		assertTrue(result.isPresent());
	}

	@Test
	void moreThanTenPicksFirstPdfInSequenceOrder() {
		List<DocumentSnapshot> pdfs = new java.util.ArrayList<>();
		// First in sequence order is DOC_C (seq 0); others follow. A LAPPe.pdf exists
		// later but must be ignored because there are more than 10 PDFs.
		pdfs.add(new DocumentSnapshot(DOC_C, 0, "documents/first.pdf"));
		for (int i = 1; i < 11; i++) {
			pdfs.add(new DocumentSnapshot(UUID.randomUUID(), i,
					i == 10 ? "documents/lappe.pdf" : "documents/f" + i + ".pdf"));
		}
		assertEquals(11, pdfs.size());
		Optional<UUID> result = DocumentCandidateSelector.select(pdfs);
		assertEquals(DOC_C, result.orElseThrow());
	}

	@Test
	void elevenWithoutLappeStillPicksFirstPdf() {
		List<DocumentSnapshot> pdfs = new java.util.ArrayList<>();
		pdfs.add(new DocumentSnapshot(DOC_C, 0, "documents/first.pdf"));
		for (int i = 1; i < 11; i++) {
			pdfs.add(snap(i, "documents/f" + i + ".pdf"));
		}
		Optional<UUID> result = DocumentCandidateSelector.select(pdfs);
		assertEquals(DOC_C, result.orElseThrow());
	}

	@Test
	void nullFilenameIsNotTreatedAsLappe() {
		Optional<UUID> result = DocumentCandidateSelector.select(List.of(
				snap(0, null),
				snap(1, "documents/other.pdf")));
		assertFalse(result.isPresent());
	}

}
