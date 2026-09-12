package horse.sumomo.pos_doc_backend.review;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic unit tests for {@link TrigramSimilarity}: exact equality, the
 * Sørensen–Dice formula, and the required edge cases (one/two characters,
 * whitespace, punctuation, mixed case, non-BMP code points) plus
 * determinism and commutativity.
 */
class TrigramSimilarityTest {

	@Test
	void equalStringsReturnOne() {
		assertEquals(1.0, TrigramSimilarity.similarity("jane tan", "jane tan"), 1e-12);
		assertEquals(1.0, TrigramSimilarity.similarity("x", "x"), 1e-12);
	}

	@Test
	void knownDiceValue() {
		// "  abc  " -> { "  a", " ab", "abc", "bc ", "c  " } (5)
		// "  abd  " -> { "  a", " ab", "abd", "bd ", "d  " } (5)
		// intersection { "  a", " ab" } (2) -> 2*2/(5+5) = 0.4
		assertEquals(0.4, TrigramSimilarity.similarity("abc", "abd"), 1e-12);
	}

	@Test
	void disjointTrigramsReturnZero() {
		assertEquals(0.0, TrigramSimilarity.similarity("ab", "ba"), 1e-12);
	}

	@Test
	void singleCharacterInputsAreHandled() {
		// "  a  " (3) vs "  b  " (3): no common trigram -> 0.
		assertEquals(0.0, TrigramSimilarity.similarity("a", "b"), 1e-12);
		// "  a  " (3) vs "  abc  " (5): intersection { "  a" } (1) -> 2*1/8 = 0.25
		assertEquals(0.25, TrigramSimilarity.similarity("a", "abc"), 1e-12);
	}

	@Test
	void twoCharacterInputsAreHandled() {
		// "  ab  " -> { "  a", " ab", "ab ", "b  " } (4); "  a  " (3):
		// intersection { "  a" } (1) -> 2*1/7
		assertEquals(2.0 / 7.0, TrigramSimilarity.similarity("ab", "a"), 1e-12);
	}

	@Test
	void whitespaceInsideTheStringProducesTrigrams() {
		// Normalized names collapse whitespace, so real inputs are single-spaced;
		// this only exercises the raw trigram mechanics on differing strings.
		double score = TrigramSimilarity.similarity("jane tan", "jane  tan");
		assertTrue(score > 0.0 && score < 1.0);
	}

	@Test
	void punctuationAffectsTrigrams() {
		assertEquals(1.0, TrigramSimilarity.similarity("p123", "p123"), 1e-12);
		double withPunct = TrigramSimilarity.similarity("p123", "p-123");
		assertTrue(withPunct > 0.0 && withPunct < 1.0);
	}

	@Test
	void mixedCaseIsTreatedLiterallyByTheUtility() {
		// The utility performs no case folding; "Jane" and "jane" are different.
		assertEquals(1.0, TrigramSimilarity.similarity("Jane", "Jane"), 1e-12);
		double score = TrigramSimilarity.similarity("Jane", "jane");
		assertTrue(score >= 0.0 && score < 1.0);
	}

	@Test
	void nonBmpCodePointsAreIteratedAsCodePoints() {
		// U+1F600 is a surrogate pair in UTF-16; a code-unit scan would split it.
		String face = "a\uD83D\uDE00"; // "a" + U+1F600
		assertEquals(1.0, TrigramSimilarity.similarity(face, face), 1e-12);
		// "  a😀  " (6 cps) vs "  a  " (5 cps): intersection { "  a" } (1) -> 2*1/7
		assertEquals(2.0 / 7.0, TrigramSimilarity.similarity(face, "a"), 1e-9);
	}

	@Test
	void twoCharacterNonBmpInputIsHandled() {
		// Two supplementary characters adjacent: U+1F600 then U+1F641.
		String two = "\uD83D\uDE00\uD83D\uDE41";
		assertEquals(1.0, TrigramSimilarity.similarity(two, two), 1e-12);
	}

	@Test
	void isDeterministicAndCommutative() {
		String left = "jose lim";
		String right = "josé lim";
		double first = TrigramSimilarity.similarity(left, right);
		double again = TrigramSimilarity.similarity(left, right);
		double swapped = TrigramSimilarity.similarity(right, left);
		assertEquals(first, again, 1e-12);
		assertEquals(first, swapped, 1e-12);
	}

	@Test
	void nullInputsAreRejected() {
		assertThrows(IllegalArgumentException.class, () -> TrigramSimilarity.similarity(null, "x"));
		assertThrows(IllegalArgumentException.class, () -> TrigramSimilarity.similarity("x", null));
	}

}
