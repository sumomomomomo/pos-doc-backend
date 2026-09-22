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

	// ---- valid names ----

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
	void unicodeLettersAndHyphensAndApostrophesAreAccepted() {
		assertEquals("José", FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "José").value());
		assertEquals("Anne-Marie", FieldAnswerParser.parse(ExtractionField.CONSULTANT_NAME, "Anne-Marie").value());
		assertEquals("O'Brien", FieldAnswerParser.parse(ExtractionField.CONSULTANT_NAME, "O'Brien").value());
	}

	@Test
	void matchedWrappingQuotesAreRemoved() {
		assertEquals("Charlie Henry", FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "\"Charlie Henry\"").value());
		assertEquals("Charlie Henry", FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "'Charlie Henry'").value());
	}

	@Test
	void trailingParenIdentificationNumberIsStripped() {
		assertEquals("Charlie Henry",
				FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "Charlie Henry (S1234567A)").value());
	}

	@Test
	void trailingBracketIdentificationNumberIsStripped() {
		assertEquals("Charlie Henry",
				FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "Charlie Henry [S1234567A]").value());
	}

	@Test
	void nameExactly256CharsIsResolved() {
		FieldAnswerParse p = FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "a".repeat(256));
		assertEquals(ParseKind.RESOLVED, p.kind());
		assertEquals(256, p.value().length());
	}

	// ---- unresolved tokens ----

	@Test
	void unknownTokensAreUnknown() {
		assertEquals(ParseKind.UNKNOWN, FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "UNKNOWN").kind());
		assertEquals(ParseKind.UNKNOWN, FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "unknown").kind());
		assertEquals(ParseKind.UNKNOWN, FieldAnswerParser.parse(ExtractionField.CONSULTANT_NAME, "N/A").kind());
		assertEquals(ParseKind.UNKNOWN, FieldAnswerParser.parse(ExtractionField.CONSULTANT_NAME, "NONE").kind());
		assertEquals(ParseKind.UNKNOWN, FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "  unknown  ").kind());
	}

	@Test
	void notFoundIsUnknownNotAName() {
		FieldAnswerParse p = FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "NOT FOUND");
		assertEquals(ParseKind.UNKNOWN, p.kind());
		assertNull(p.value());
	}

	@Test
	void unknownNameValueIsNull() {
		FieldAnswerParse p = FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "UNKNOWN");
		assertEquals(ParseKind.UNKNOWN, p.kind());
		assertNull(p.value());
	}

	// ---- invalid names ----

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
	void nameWithoutAnyLetterIsInvalid() {
		assertEquals(ParseKind.INVALID, FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "12345").kind());
		assertEquals(ParseKind.INVALID, FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "   ").kind());
	}

	@Test
	void digitsInNameAreRejected() {
		assertEquals(ParseKind.INVALID,
				FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "Charlie Henry123").kind());
	}

	@Test
	void controlCharactersAreRejected() {
		assertEquals(ParseKind.INVALID,
				FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "John\u0000Doe").kind());
		assertEquals(ParseKind.INVALID,
				FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "John\u0001Doe").kind());
		assertEquals(ParseKind.INVALID,
				FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "John\u007fDoe").kind());
	}

	@Test
	void newlineIsRejectedNotCollapsed() {
		assertEquals(ParseKind.INVALID,
				FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "Charlie Henry\nAdditional explanation...").kind());
		assertEquals(ParseKind.INVALID,
				FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "Charlie\rHenry").kind());
		assertEquals(ParseKind.INVALID,
				FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "Charlie\n\nHenry").kind());
	}

	@Test
	void unmatchedLeadingQuoteIsRejected() {
		assertEquals(ParseKind.INVALID,
				FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "\"Charlie Henry").kind());
		assertEquals(ParseKind.INVALID,
				FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "'Charlie Henry").kind());
	}

	@Test
	void backtickIsNotTreatedAsAQuote() {
		// Backticks are not quote delimiters; the value keeps them and is rejected.
		assertEquals(ParseKind.INVALID,
				FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "`Charlie Henry`").kind());
	}

	@Test
	void fieldLabelIsRejected() {
		assertEquals(ParseKind.INVALID,
				FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "Policyowner Name: Charlie Henry").kind());
		assertEquals(ParseKind.INVALID,
				FieldAnswerParser.parse(ExtractionField.CONSULTANT_NAME, "Consultant: John Davidson").kind());
	}

	@Test
	void listMarkerIsRejected() {
		assertEquals(ParseKind.INVALID,
				FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "1. Charlie Henry").kind());
		assertEquals(ParseKind.INVALID,
				FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "2) Charlie Henry").kind());
		assertEquals(ParseKind.INVALID,
				FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "- Charlie Henry").kind());
	}

	@Test
	void alternativeIsRejected() {
		assertEquals(ParseKind.INVALID,
				FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "Charlie Henry or Charles Henry").kind());
		assertEquals(ParseKind.INVALID,
				FieldAnswerParser.parse(ExtractionField.CONSULTANT_NAME, "John or Jane").kind());
	}

	@Test
	void proseIsRejected() {
		// An ellipsis is not a name character (periods are only allowed in single-letter initials).
		assertEquals(ParseKind.INVALID,
				FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "Additional explanation...").kind());
		// A label/prose prefix ("The policyholder is ...") is not a bare name, even with no colon.
		assertEquals(ParseKind.INVALID,
				FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "The policyholder is Charlie Henry").kind());
	}

	@Test
	void labelProsePrefixesAreRejected() {
		// Answers carrying a known label/prose prefix are rejected even when they
		// contain only letters/spaces and no colon.
		assertEquals(ParseKind.INVALID,
				FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "Policyowner Name Charlie Henry").kind());
		assertEquals(ParseKind.INVALID,
				FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "The name is Charlie Henry").kind());
		assertEquals(ParseKind.INVALID,
				FieldAnswerParser.parse(ExtractionField.CONSULTANT_NAME, "Consultant Name John Davidson").kind());
		assertEquals(ParseKind.INVALID,
				FieldAnswerParser.parse(ExtractionField.CONSULTANT_NAME, "Financial Consultant Name John Davidson").kind());
	}

	@Test
	void surnameContainingLabelWordIsResolved() {
		// "Date" is a real surname, not a label: it must be resolved, not rejected.
		FieldAnswerParse p = FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "Masamune Date");
		assertEquals(ParseKind.RESOLVED, p.kind());
		assertEquals("Masamune Date", p.value());
	}

	@Test
	void initialsWithPeriodsAreResolved() {
		// Periods used in single-letter name initials are permitted.
		FieldAnswerParse p = FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "A. K. Tan");
		assertEquals(ParseKind.RESOLVED, p.kind());
		assertEquals("A. K. Tan", p.value());
	}

	@Test
	void twoLetterNameComponentsAreResolved() {
		// Ordinary two-letter name words (common in Singaporean names) are regular
		// words, not initials: they must be accepted, not rejected as malformed initials.
		assertEquals("Li Wei",
				FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "Li Wei").value());
		assertEquals("Ng Wei",
				FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "Ng Wei").value());
		assertEquals("Wu Bo",
				FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "Wu Bo").value());
	}

	@Test
	void nonInitialTrailingPeriodsAreInvalid() {
		// A period after a multi-letter word ("Tan.") or after two letters ("AB.") is
		// not a single-letter initial and remains invalid.
		assertEquals(ParseKind.INVALID,
				FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "Tan.").kind());
		assertEquals(ParseKind.INVALID,
				FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "AB.").kind());
	}

	@Test
	void ellipsisIsStillInvalid() {
		// Ellipses (and periods that are not single-letter initials) remain invalid.
		assertEquals(ParseKind.INVALID,
				FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "Additional explanation...").kind());
	}

	@Test
	void validNameLongerThanFourWordsIsResolved() {
		// No word-count cap: a legitimate multi-word name is accepted.
		FieldAnswerParse p = FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME,
				"Mary Anne Patricia Rosemary Smith");
		assertEquals(ParseKind.RESOLVED, p.kind());
		assertEquals("Mary Anne Patricia Rosemary Smith", p.value());
	}

	@Test
	void trailingNewlineIsTrimmedNotRejected() {
		// Leading/trailing whitespace (including a trailing newline) is trimmed first;
		// only an internal newline is invalid.
		FieldAnswerParse p = FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "Charlie Henry\n");
		assertEquals(ParseKind.RESOLVED, p.kind());
		assertEquals("Charlie Henry", p.value());
		assertEquals("John Davidson",
				FieldAnswerParser.parse(ExtractionField.CONSULTANT_NAME, "  John Davidson  ").value());
	}

	@Test
	void unknownWithTrailingIdIsUnknownNotAName() {
		// The policyholder ID is removed first, then the sentinel is detected: the
		// result is an unresolved token, never a resolved name.
		FieldAnswerParse p = FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "UNKNOWN (123)");
		assertEquals(ParseKind.UNKNOWN, p.kind());
		assertNull(p.value());
		assertEquals(ParseKind.UNKNOWN,
				FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "NOT FOUND [S1234567A]").kind());
		assertEquals(ParseKind.UNKNOWN,
				FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "NONE (S1)").kind());
	}

	@Test
	void policyholderIdStillStrippedForRealName() {
		assertEquals("Charlie Henry",
				FieldAnswerParser.parse(ExtractionField.POLICYHOLDER_NAME, "Charlie Henry (S1234567A)").value());
	}

	@Test
	void nullFieldIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> FieldAnswerParser.parse(null, "x"));
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
		assertEquals(ParseKind.INVALID,
				FieldAnswerParser.parse(ExtractionField.POLICY_CREATE_DATE, "30-Feb-2026").kind());
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
	void quotedDateIsAccepted() {
		FieldAnswerParse p = FieldAnswerParser.parse(ExtractionField.POLICY_CREATE_DATE, "\"26-Jul-2026\"");
		assertEquals(ParseKind.RESOLVED, p.kind());
		assertEquals("2026-07-26", p.value());
	}

	@Test
	void dateWithNewlineIsRejected() {
		assertEquals(ParseKind.INVALID,
				FieldAnswerParser.parse(ExtractionField.POLICY_CREATE_DATE, "26-Jul-2026\nextra").kind());
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
				FieldAnswerParser.parse(ExtractionField.POLICY_CREATE_DATE, "NOT FOUND").kind());
	}

}
