package horse.sumomo.pos_doc_backend.ocr.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import horse.sumomo.pos_doc_backend.persistence.model.ExtractionField;
import horse.sumomo.pos_doc_backend.ocr.application.FieldAnswerParse.ParseKind;

/**
 * Unit tests for {@link FieldAnswerParser}. Pure logic, no I/O.
 */
class FieldAnswerParserTest {

	// ---- normalize ----

	@Test
	void normalizeTrimsAndCollapsesWhitespace() {
		assertEquals("Charlie Henry", FieldAnswerParser.normalize("   Charlie   Henry   "));
		assertEquals("Charlie Henry", FieldAnswerParser.normalize("Charlie\n\n  Henry"));
		assertEquals("Charlie Henry", FieldAnswerParser.normalize("Charlie\tHenry"));
	}

	@Test
	void normalizeStripsWrappingQuotes() {
		assertEquals("Charlie Henry", FieldAnswerParser.normalize("\"Charlie Henry\""));
		assertEquals("Charlie Henry", FieldAnswerParser.normalize("'Charlie Henry'"));
	}

	@Test
	void normalizeStripsLeadingQuoteOnly() {
		assertEquals("Charlie Henry", FieldAnswerParser.normalize("\"Charlie Henry"));
	}

	@Test
	void normalizeLeavesTrailingOnlyQuote() {
		// Only leading or wrapping quotes are removed; a lone trailing quote is kept.
		assertEquals("Charlie Henry\"", FieldAnswerParser.normalize("Charlie Henry\""));
	}

	@Test
	void normalizeNullIsEmpty() {
		assertEquals("", FieldAnswerParser.normalize(null));
	}

	// ---- names ----

	@Test
	void validNameIsResolved() {
		FieldAnswerParse p = FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "Charlie Henry");
		assertEquals(ParseKind.RESOLVED, p.kind());
		assertEquals("Charlie Henry", p.value());
	}

	@Test
	void namePreservesCase() {
		FieldAnswerParse p = FieldAnswerParser.parse(ExtractionField.CONSULTANT_NAME, "John Davidson");
		assertEquals(ParseKind.RESOLVED, p.kind());
		assertEquals("John Davidson", p.value());
	}

	@Test
	void blankNameIsInvalid() {
		assertEquals(ParseKind.INVALID, FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "   ").kind());
		assertEquals(ParseKind.INVALID, FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, null).kind());
	}

	@Test
	void nameOver256CharsIsInvalid() {
		assertEquals(ParseKind.INVALID,
				FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "a".repeat(257)).kind());
	}

	@Test
	void nameExactly256CharsIsResolved() {
		FieldAnswerParse p = FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "a".repeat(256));
		assertEquals(ParseKind.RESOLVED, p.kind());
		assertEquals(256, p.value().length());
	}

	@Test
	void nameWithNulIsInvalid() {
		assertEquals(ParseKind.INVALID,
				FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "John\u0000Doe").kind());
	}

	@Test
	void nameWithControlCharacterIsInvalid() {
		assertEquals(ParseKind.INVALID,
				FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "John\u0001Doe").kind());
		assertEquals(ParseKind.INVALID,
				FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "John\u007fDoe").kind());
	}

	@Test
	void nameUnknownTokensAreUnknownNotInvalid() {
		assertEquals(ParseKind.UNKNOWN, FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "UNKNOWN").kind());
		assertEquals(ParseKind.UNKNOWN, FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "Unknown").kind());
		assertEquals(ParseKind.UNKNOWN, FieldAnswerParser.parse(ExtractionField.CONSULTANT_NAME, "none").kind());
		assertEquals(ParseKind.UNKNOWN, FieldAnswerParser.parse(ExtractionField.CONSULTANT_NAME, "N/A").kind());
		assertEquals(ParseKind.UNKNOWN, FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "  unknown  ").kind());
	}

	@Test
	void unknownNameValueIsNull() {
		FieldAnswerParse p = FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "UNKNOWN");
		assertEquals(ParseKind.UNKNOWN, p.kind());
		assertNull(p.value());
	}

	// ---- dates ----

	@Test
	void validDateIsCanonicalizedToIso() {
		FieldAnswerParse p = FieldAnswerParser.parse(ExtractionField.POLICY_CREATE_DATE, "26-Jul-2026");
		assertEquals(ParseKind.RESOLVED, p.kind());
		assertEquals("2026-07-26", p.value());
	}

	@Test
	void dateMonthAbbreviationIsCaseInsensitive() {
		assertEquals("2025-07-07", FieldAnswerParser.parse(ExtractionField.POLICY_CREATE_DATE, "07-JUL-2025").value());
		assertEquals("2025-07-07", FieldAnswerParser.parse(ExtractionField.POLICY_CREATE_DATE, "07-jul-2025").value());
		assertEquals("2025-01-31", FieldAnswerParser.parse(ExtractionField.POLICY_CREATE_DATE, "31-Jan-2025").value());
		assertEquals("2025-12-25", FieldAnswerParser.parse(ExtractionField.POLICY_CREATE_DATE, "25-DEC-2025").value());
	}

	@Test
	void leapDayIsAccepted() {
		assertEquals("2024-02-29", FieldAnswerParser.parse(ExtractionField.POLICY_CREATE_DATE, "29-Feb-2024").value());
	}

	@Test
	void invalidCalendarDateIsRejected() {
		// Feb 30 never exists.
		assertEquals(ParseKind.INVALID,
				FieldAnswerParser.parse(ExtractionField.POLICY_CREATE_DATE, "30-Feb-2026").kind());
		// Feb 29 in a non-leap year.
		assertEquals(ParseKind.INVALID,
				FieldAnswerParser.parse(ExtractionField.POLICY_CREATE_DATE, "29-Feb-2025").kind());
	}

	@Test
	void nonMatchingDateFormatsAreRejected() {
		assertEquals(ParseKind.INVALID,
				FieldAnswerParser.parse(ExtractionField.POLICY_CREATE_DATE, "2025-07-07").kind()); // ISO
		assertEquals(ParseKind.INVALID,
				FieldAnswerParser.parse(ExtractionField.POLICY_CREATE_DATE, "07/07/2025").kind()); // dd/MM/yyyy
		assertEquals(ParseKind.INVALID,
				FieldAnswerParser.parse(ExtractionField.POLICY_CREATE_DATE, "July 26, 2026").kind()); // prose
		assertEquals(ParseKind.INVALID,
				FieldAnswerParser.parse(ExtractionField.POLICY_CREATE_DATE, "26-Jul-26").kind()); // short year
	}

	@Test
	void dateWithSurroundingWhitespaceIsAccepted() {
		// Whitespace is trimmed during normalization, so " 26-Jul-2026 " is valid.
		FieldAnswerParse p = FieldAnswerParser.parse(ExtractionField.POLICY_CREATE_DATE, " 26-Jul-2026 ");
		assertEquals(ParseKind.RESOLVED, p.kind());
		assertEquals("2026-07-26", p.value());
	}

	@Test
	void blankDateIsInvalid() {
		assertEquals(ParseKind.INVALID, FieldAnswerParser.parse(ExtractionField.POLICY_CREATE_DATE, "").kind());
	}

	@Test
	void dateUnknownTokensAreUnknown() {
		assertEquals(ParseKind.UNKNOWN,
				FieldAnswerParser.parse(ExtractionField.POLICY_CREATE_DATE, "UNKNOWN").kind());
		assertEquals(ParseKind.UNKNOWN,
				FieldAnswerParser.parse(ExtractionField.POLICY_CREATE_DATE, "N/A").kind());
	}

	@Test
	void nullFieldIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> FieldAnswerParser.parse(null, "x"));
	}

}
