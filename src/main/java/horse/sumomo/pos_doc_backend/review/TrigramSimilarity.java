package horse.sumomo.pos_doc_backend.review;

import java.util.HashSet;
import java.util.Set;

/**
 * Deterministic Sørensen–Dice trigram similarity for policyholder-name search.
 *
 * <p>Inputs are already-normalized, nonblank strings (see
 * {@link horse.sumomo.pos_doc_backend.persistence.normalization.MetadataNormalizer});
 * this utility performs no normalization of its own. The algorithm is fixed and
 * locale-independent:
 *
 * <ol>
 *   <li>Pad each entire string with two ASCII spaces on both sides.</li>
 *   <li>Produce the set of distinct consecutive three-code-point substrings,
 *       iterating by Unicode code point rather than UTF-16 code unit.</li>
 *   <li>Return {@code 1.0} when the two strings are equal.</li>
 *   <li>Otherwise return the Sørensen–Dice score
 *       {@code 2 * |intersection| / (|left| + |right|)}.</li>
 *   <li>Clamp only numerical roundoff to {@code [0.0, 1.0]}.</li>
 * </ol>
 *
 * <p>Because both inputs are padded, even a single-character input yields at
 * least three trigrams, so the denominator is never zero for valid inputs.
 */
public final class TrigramSimilarity {

	private static final String PADDING = "  ";

	private TrigramSimilarity() {
	}

	/**
	 * @param left  an already-normalized, nonblank string
	 * @param right an already-normalized, nonblank string
	 * @return similarity in {@code [0.0, 1.0]}
	 */
	public static double similarity(String left, String right) {
		if (left == null || right == null) {
			throw new IllegalArgumentException("both inputs must be non-null");
		}
		if (left.equals(right)) {
			return 1.0;
		}
		Set<String> leftGrams = trigrams(left);
		Set<String> rightGrams = trigrams(right);
		int intersection = 0;
		for (String gram : leftGrams) {
			if (rightGrams.contains(gram)) {
				intersection++;
			}
		}
		int denominator = leftGrams.size() + rightGrams.size();
		if (denominator == 0) {
			// Unreachable for nonblank padded inputs; guard against division by zero.
			return 0.0;
		}
		double score = (2.0 * intersection) / denominator;
		if (score < 0.0) {
			return 0.0;
		}
		if (score > 1.0) {
			return 1.0;
		}
		return score;
	}

	private static Set<String> trigrams(String value) {
		String padded = PADDING + value + PADDING;
		int[] codePoints = padded.codePoints().toArray();
		Set<String> grams = new HashSet<>();
		for (int i = 0; i + 2 < codePoints.length; i++) {
			StringBuilder sb = new StringBuilder(4);
			sb.appendCodePoint(codePoints[i]);
			sb.appendCodePoint(codePoints[i + 1]);
			sb.appendCodePoint(codePoints[i + 2]);
			grams.add(sb.toString());
		}
		return grams;
	}

}
