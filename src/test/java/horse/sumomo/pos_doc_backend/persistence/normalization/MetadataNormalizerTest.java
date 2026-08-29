package horse.sumomo.pos_doc_backend.persistence.normalization;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Focused unit tests for {@link MetadataNormalizer}: null handling, blank
 * rejection, Unicode compatibility characters, case conversion, punctuation
 * handling, and whitespace collapsing.
 */
class MetadataNormalizerTest {

	@Test
	void identifierNullReturnsNull() {
		assertNull(MetadataNormalizer.normalizeIdentifier(null));
	}

	@Test
	void nameNullReturnsNull() {
		assertNull(MetadataNormalizer.normalizeName(null));
	}

	@Test
	void identifierWhitespaceOnlyIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> MetadataNormalizer.normalizeIdentifier("   "));
	}

	@Test
	void identifierPunctuationOnlyIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> MetadataNormalizer.normalizeIdentifier("---///"));
	}

	@Test
	void nameWhitespaceOnlyIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> MetadataNormalizer.normalizeName(" \t\n "));
	}

	@Test
	void identifierTrimsUppercasesAndRemovesPunctuation() {
		assertEquals("EREF2026001", MetadataNormalizer.normalizeIdentifier(" EREF-2026 001 "));
	}

	@Test
	void identifierKeepsOnlyLettersAndDecimalDigits() {
		assertEquals("P12345", MetadataNormalizer.normalizeIdentifier("p-123/45"));
	}

	@Test
	void identifierAppliesNfkcCompatibilityDecomposition() {
		// Fullwidth A/B and the fi ligature U+FB01 collapse under NFKC.
		assertEquals("ABCFI123", MetadataNormalizer.normalizeIdentifier("\uFF21\uFF22C\uFB01123"));
	}

	@Test
	void identifierAppliesLocaleRootUppercasing() {
		// The Turkish dotted capital I (U+0130) stays U+0130 under Locale.ROOT
		// uppercasing (it is not folded to "I" as it would be under the TR
		// locale), and the dotless i (U+015F) uppercases to U+015E. This proves
		// the conversion is Locale.ROOT-based, not host-locale-based.
		assertEquals("I\u015ETANBUL1", MetadataNormalizer.normalizeIdentifier("i\u015Ftanbul-1"));
	}

	@Test
	void identifierHandlesNonAsciiDecimalDigits() {
		// U+0661 is an Arabic-Indic decimal digit. NFKC does not decompose it
		// to an ASCII digit, and the rule keeps it (it is a decimal digit), so
		// it survives normalization unchanged.
		assertEquals("A\u0661", MetadataNormalizer.normalizeIdentifier("a\u0661"));
	}

	@Test
	void nameTrimsLowercasesAndCollapsesWhitespace() {
		assertEquals("jane tan", MetadataNormalizer.normalizeName("  Jane   TAN "));
	}

	@Test
	void namePreservesAccentsAndNonLatinCharacters() {
		assertEquals("josé lim", MetadataNormalizer.normalizeName("José Lim"));
		assertEquals("zhang wei", MetadataNormalizer.normalizeName("ZHANG  wei"));
	}

	@Test
	void nameAppliesNfkcCompatibilityDecomposition() {
		// Fullwidth Latin letters normalize to their ASCII equivalents under NFKC
		// (U+FF4A->J, U+FF41->A, U+FF4E->N, U+FF45->E, U+FF54->T).
		assertEquals("jane tan",
				MetadataNormalizer.normalizeName("\uFF4A\uFF41\uFF4E\uFF45 \uFF54\uFF41\uFF4E"));
	}

	@Test
	void nameCollapsesNonSpaceUnicodeWhitespaceToAsciiSpace() {
		// NBSP and EM SPACE are Unicode whitespace, not ASCII space.
		assertEquals("jane tan", MetadataNormalizer.normalizeName("Jane\u00A0\u2003Tan"));
		assertEquals("jane tan", MetadataNormalizer.normalizeName("Jane\u3000Tan"));
	}

}
