package horse.sumomo.pos_doc_backend.ingestion.application;

import java.util.Comparator;
import java.util.List;

/**
 * Pure selection rule for the structured field-extraction candidate documents.
 *
 * <p>Given the record's PDFs (in ZIP entry/sequence order) the candidates are:
 * <ul>
 *   <li>every PDF whose basename ends <em>case-sensitively</em> in
 *       {@code LAPPe.pdf}, in sequence order; or</li>
 *   <li>if no PDF matches, the first up to ten PDFs, in sequence order.</li>
 * </ul>
 *
 * <p>The caller processes the returned candidates <em>sequentially</em>,
 * requesting only fields that are still unresolved, and stops once every field
 * resolves. This class performs no I/O and owns no database, messaging, or HTTP
 * dependencies, so it can be unit-tested with ordinary inputs.
 *
 * <p>Matching is case-sensitive on the stored basename; the stored filename
 * therefore preserves the original case (see
 * {@code ArchiveExtractionService#lastSegment}).
 */
public final class DocumentCandidateSelector {

	/** Fallback cap: when no {@code LAPPe.pdf} matches, at most this many PDFs become candidates. */
	private static final int MAX_FALLBACK_CANDIDATES = 10;

	/** The exact, case-sensitive candidate basename suffix. */
	private static final String LAPPE_SUFFIX = "LAPPe.pdf";

	private DocumentCandidateSelector() {
	}

	/**
	 * Selects the candidate documents for structured field extraction.
	 *
	 * @param pdfs the record's PDFs (each snapshot carries id, sequence order,
	 *        and stored filename); may be empty
	 * @return the candidates in sequence order: every case-sensitive
	 *         {@code LAPPe.pdf} match, or (when there is no match) the first up
	 *         to {@value #MAX_FALLBACK_CANDIDATES} PDFs; empty when there are no
	 *         PDFs
	 */
	public static List<DocumentSnapshot> select(List<DocumentSnapshot> pdfs) {
		if (pdfs == null || pdfs.isEmpty()) {
			return List.of();
		}
		List<DocumentSnapshot> ordered =
				pdfs.stream().sorted(Comparator.comparingInt(DocumentSnapshot::sequenceNumber)).toList();
		List<DocumentSnapshot> lappe = ordered.stream()
				.filter(DocumentCandidateSelector::isLappe)
				.toList();
		if (!lappe.isEmpty()) {
			return lappe;
		}
		return ordered.stream().limit(MAX_FALLBACK_CANDIDATES).toList();
	}

	private static boolean isLappe(DocumentSnapshot snapshot) {
		String filename = snapshot.filename();
		if (filename == null || filename.isBlank()) {
			return false;
		}
		int slash = filename.lastIndexOf('/');
		String basename = slash >= 0 ? filename.substring(slash + 1) : filename;
		return basename.endsWith(LAPPE_SUFFIX);
	}

}
