package horse.sumomo.pos_doc_backend.persistence.normalization;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Deterministic normalization for searchable metadata.
 *
 * <p>Stateless utility with no database or network dependency. The rules are
 * fixed so that values written at upload time stay comparable across
 * requests:
 *
 * <ul>
 *   <li>{@link #normalizeIdentifier(String)}: Unicode NFKC, uppercase
 *       ({@link Locale#ROOT}), keep only Unicode letters and decimal digits.
 *       Used for eRef and policy numbers.</li>
 *   <li>{@link #normalizeName(String)}: Unicode NFKC, trim Unicode
 *       whitespace, lowercase ({@link Locale#ROOT}), collapse every run of
 *       Unicode whitespace to one ASCII space. Used for policyholder names;
 *       accents, punctuation, and non-Latin characters are preserved.</li>
 * </ul>
 *
 * <p>Both methods return {@code null} for {@code null} input and reject an
 * input that becomes empty after normalization with
 * {@link IllegalArgumentException}.
 */
public final class MetadataNormalizer {

	private MetadataNormalizer() {
	}

	public static String normalizeIdentifier(String value) {
		if (value == null) {
			return null;
		}
		String upper = Normalizer.normalize(value, Normalizer.Form.NFKC).toUpperCase(Locale.ROOT);
		StringBuilder result = new StringBuilder(upper.length());
		for (int i = 0; i < upper.length(); i++) {
			char c = upper.charAt(i);
			if (Character.isLetter(c) || Character.getType(c) == Character.DECIMAL_DIGIT_NUMBER) {
				result.append(c);
			}
		}
		if (result.isEmpty()) {
			throw new IllegalArgumentException("value is empty after identifier normalization");
		}
		return result.toString();
	}

	public static String normalizeName(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = trimUnicodeWhitespace(Normalizer.normalize(value, Normalizer.Form.NFKC));
		String lower = trimmed.toLowerCase(Locale.ROOT);
		StringBuilder result = new StringBuilder(lower.length());
		boolean previousWhitespace = false;
		for (int i = 0; i < lower.length(); i++) {
			char c = lower.charAt(i);
			if (Character.isWhitespace(c)) {
				previousWhitespace = true;
			}
			else {
				if (previousWhitespace && result.length() > 0) {
					result.append(' ');
				}
				result.append(c);
				previousWhitespace = false;
			}
		}
		if (result.isEmpty()) {
			throw new IllegalArgumentException("value is empty after name normalization");
		}
		return result.toString();
	}

	private static String trimUnicodeWhitespace(String value) {
		int start = 0;
		int end = value.length();
		while (start < end && Character.isWhitespace(value.charAt(start))) {
			start++;
		}
		while (end > start && Character.isWhitespace(value.charAt(end - 1))) {
			end--;
		}
		return value.substring(start, end);
	}

}
