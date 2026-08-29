package horse.sumomo.pos_doc_backend.ingestion.archive;

/**
 * Non-sensitive structural facts about a ZIP archive that passed validation.
 *
 * <p>Contains only counts and byte totals: no entry filenames, object keys,
 * hashes, or contents. {@link #toString()} is therefore safe to log.
 */
public record ValidatedArchive(int pdfCount, long totalUncompressedBytes) {

	public ValidatedArchive {
		if (pdfCount < 1) {
			throw new IllegalArgumentException("pdfCount must be >= 1");
		}
		if (totalUncompressedBytes < 0) {
			throw new IllegalArgumentException("totalUncompressedBytes must be >= 0");
		}
	}

	@Override
	public String toString() {
		return "ValidatedArchive[pdfCount=" + this.pdfCount + ", totalUncompressedBytes="
				+ this.totalUncompressedBytes + "]";
	}

}
