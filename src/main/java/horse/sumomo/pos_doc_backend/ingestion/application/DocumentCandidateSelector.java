package horse.sumomo.pos_doc_backend.ingestion.application;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Pure selection rule for the structured field-extraction candidate document.
 *
 * <p>Given the record's PDFs (in ZIP entry/sequence order):
 * <ul>
 *   <li>exactly one PDF: that PDF is the candidate;</li>
 *   <li>two to ten PDFs: the first (lowest sequence number) whose basename ends in
 *       {@code LAPPe.pdf} (case-insensitive); none is selected if no filename
 *       matches;</li>
 *   <li>more than ten PDFs: the first PDF (lowest sequence number) is selected.</li>
 * </ul>
 *
 * <p>This class performs no I/O and owns no database, messaging, or HTTP
 * dependencies, so it can be unit-tested with ordinary inputs.
 */
public final class DocumentCandidateSelector {

	private static final int MAX_PDFS_WITH_NAME_PREFERENCE = 10;

	private static final String LAPPE_SUFFIX = "lappe.pdf";

	private DocumentCandidateSelector() {
	}

	/**
	 * Selects the candidate document for structured field extraction.
	 *
	 * @param pdfs the record's PDFs (each snapshot carries id, sequence order,
	 *        and stored filename); may be empty
	 * @return the candidate document id, or empty when there are no PDFs (or no
	 *         {@code LAPPe.pdf} match in the 2–10 range)
	 */
	public static Optional<UUID> select(List<DocumentSnapshot> pdfs) {
		if (pdfs == null || pdfs.isEmpty()) {
			return Optional.empty();
		}
		List<DocumentSnapshot> ordered =
				pdfs.stream().sorted(Comparator.comparingInt(DocumentSnapshot::sequenceNumber)).toList();
		int count = ordered.size();
		if (count == 1) {
			return Optional.of(ordered.get(0).documentId());
		}
		if (count <= MAX_PDFS_WITH_NAME_PREFERENCE) {
			return ordered.stream().filter(DocumentCandidateSelector::isLappe)
					.map(DocumentSnapshot::documentId)
					.findFirst();
		}
		return Optional.of(ordered.get(0).documentId());
	}

	private static boolean isLappe(DocumentSnapshot snapshot) {
		String filename = snapshot.filename();
		if (filename == null || filename.isBlank()) {
			return false;
		}
		int slash = filename.lastIndexOf('/');
		String basename = slash >= 0 ? filename.substring(slash + 1) : filename;
		return basename.toLowerCase(Locale.ROOT).endsWith(LAPPE_SUFFIX);
	}

}
