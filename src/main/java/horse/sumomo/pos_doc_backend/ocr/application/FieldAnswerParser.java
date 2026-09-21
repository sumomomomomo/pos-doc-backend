package horse.sumomo.pos_doc_backend.ocr.application;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import horse.sumomo.pos_doc_backend.persistence.model.ExtractionField;

/**
 * Pure, strict normalization and validation for one structured field answer.
 *
 * <p>No I/O, no randomness, no PII in logs. The parser returns one of three
 * outcomes ({@link FieldAnswerParse.ParseKind}):
 * <ul>
 *   <li>{@code RESOLVED} — a clean canonical value (a validated name, or an ISO
 *       {@code yyyy-MM-dd} date);</li>
 *   <li>{@code UNKNOWN} — the model answered an explicit unresolved token
 *       ({@code UNKNOWN}, {@code NOT FOUND}, {@code N/A}, ...); a valid
 *       unresolved result, not an error;</li>
 *   <li>{@code INVALID} — the answer is not a single clean value (multi-line, an
 *       unmatched quote, a field label, prose, a list marker, an "A or B"
 *       alternative, no letter, a disallowed character, ...). The workflow treats
 *       this as a failed attempt and retries.</li>
 * </ul>
 *
 * <p>Names must be a single line of letters, spaces, apostrophes and hyphens,
 * contain at least one Unicode letter, be at most 256 characters, and be at most
 * four words. A trailing bracketed identification number (e.g.
 * {@code (S1234567A)} or {@code [S1234567A]}) is stripped before validation.
 * Newlines are rejected (not collapsed). Only a matched pair of surrounding
 * quotes is removed; an unmatched quote is rejected. Backticks are not treated
 * as quotes.
 */
public final class FieldAnswerParser {

	private static final int MAX_NAME_LENGTH = 256;

	private static final int MAX_NAME_WORDS = 4;

	/** Strict shape for the submission date: two-digit day, three-letter month, four-digit year. */
	private static final Pattern DATE_PATTERN = Pattern.compile("^(\\d{2})-([A-Za-z]{3})-(\\d{4})$");

	/** A leading list marker: "1. ", "2) ", or a bullet ("- ", "* ", "• ", "· ") followed by whitespace. */
	private static final Pattern LIST_MARKER = Pattern.compile("^(?:\\d+[.)]|[\\u2022\\u00b7*\\-])\\s");

	/** A standalone "or" (an "A or B" alternative), case-insensitive. */
	private static final Pattern ALTERNATIVE = Pattern.compile("(?i)(^|\\s)or(\\s|$)");

	/** A trailing bracketed identification number: "(S1234567A)" or "[S1234567A]". */
	private static final Pattern TRAILING_ID =
			Pattern.compile("[ \\t]*[\\(\\[] ?[0-9A-Za-z]*\\d[0-9A-Za-z]* ?[\\)\\]]$");

	/** Explicit unresolved tokens (matched case-insensitively after normalization). */
	private static final Set<String> UNKNOWN_TOKENS = Set.of(
			"UNKNOWN", "NOT FOUND", "N/A", "NONE", "NOT AVAILABLE", "NOT PROVIDED",
			"NOT SPECIFIED", "UNSPECIFIED", "NOT APPLICABLE");

	private FieldAnswerParser() {
	}

	/**
	 * Parses and validates a raw model answer for the given field.
	 *
	 * @param field the field the answer is for; must not be null
	 * @param raw the raw model response text (may be {@code null})
	 * @return a {@link FieldAnswerParse} describing the outcome
	 */
	public static FieldAnswerParse parse(ExtractionField field, String raw) {
		if (field == null) {
			throw new IllegalArgumentException("field must not be null");
		}
		if (raw == null) {
			return FieldAnswerParse.invalid();
		}
		// A single clean value must not span multiple lines. Newlines/CR are
		// rejected, not collapsed.
		if (hasVerticalWhitespace(raw)) {
			return FieldAnswerParse.invalid();
		}
		String s = raw.trim();
		// Remove a matched pair of surrounding quotes; an unmatched opening quote
		// is not silently dropped.
		if (!s.isEmpty() && (s.charAt(0) == '"' || s.charAt(0) == '\'')) {
			if (s.length() < 2 || s.charAt(s.length() - 1) != s.charAt(0)) {
				return FieldAnswerParse.invalid();
			}
			s = s.substring(1, s.length() - 1).trim();
		}
		// Collapse horizontal whitespace (spaces, tabs) to single spaces.
		s = s.replaceAll("[ \\t]+", " ");
		if (s.isEmpty()) {
			return FieldAnswerParse.invalid();
		}
		if (isUnknownToken(s)) {
			return FieldAnswerParse.unknown();
		}
		if (field == ExtractionField.POLICY_CREATE_DATE) {
			return parseDate(s);
		}
		return parseName(s);
	}

	private static FieldAnswerParse parseName(String s) {
		// A colon indicates a field label ("Name: ...") or prose, not a bare name.
		if (s.indexOf(':') >= 0) {
			return FieldAnswerParse.invalid();
		}
		if (LIST_MARKER.matcher(s).find()) {
			return FieldAnswerParse.invalid();
		}
		if (ALTERNATIVE.matcher(s).find()) {
			return FieldAnswerParse.invalid();
		}
		// Strip a trailing bracketed identification number before validating.
		s = TRAILING_ID.matcher(s).replaceFirst("").trim();
		if (s.isEmpty() || s.length() > MAX_NAME_LENGTH) {
			return FieldAnswerParse.invalid();
		}
		if (s.split(" ").length > MAX_NAME_WORDS) {
			return FieldAnswerParse.invalid();
		}
		if (!containsUnicodeLetter(s) || !isNameLike(s)) {
			return FieldAnswerParse.invalid();
		}
		return FieldAnswerParse.resolved(s);
	}

	private static FieldAnswerParse parseDate(String s) {
		Matcher m = DATE_PATTERN.matcher(s);
		if (!m.matches()) {
			return FieldAnswerParse.invalid();
		}
		int month = monthNumber(m.group(2).toUpperCase(Locale.ROOT));
		if (month == 0) {
			return FieldAnswerParse.invalid();
		}
		int day = Integer.parseInt(m.group(1));
		int year = Integer.parseInt(m.group(3));
		try {
			return FieldAnswerParse.resolved(LocalDate.of(year, month, day).toString());
		}
		catch (DateTimeException e) {
			return FieldAnswerParse.invalid();
		}
	}

	private static boolean isUnknownToken(String s) {
		return UNKNOWN_TOKENS.contains(s.toUpperCase(Locale.ROOT));
	}

	private static boolean hasVerticalWhitespace(String s) {
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '\n' || c == '\r' || c == '\f' || c == '\u000b') {
				return true;
			}
		}
		return false;
	}

	private static boolean containsUnicodeLetter(String s) {
		for (int i = 0; i < s.length(); i++) {
			if (Character.isLetter(s.charAt(i))) {
				return true;
			}
		}
		return false;
	}

	private static boolean isNameLike(String s) {
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			boolean allowed = Character.isLetter(c) || c == ' ' || c == '\'' || c == '\u2019'
					|| c == '-' || c == '\u2010' || c == '\u2011' || c == '\u2013';
			if (!allowed) {
				return false;
			}
		}
		return true;
	}

	private static int monthNumber(String abbr) {
		return switch (abbr) {
			case "JAN" -> 1;
			case "FEB" -> 2;
			case "MAR" -> 3;
			case "APR" -> 4;
			case "MAY" -> 5;
			case "JUN" -> 6;
			case "JUL" -> 7;
			case "AUG" -> 8;
			case "SEP" -> 9;
			case "OCT" -> 10;
			case "NOV" -> 11;
			case "DEC" -> 12;
			default -> 0;
		};
	}

}
