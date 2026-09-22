package horse.sumomo.pos_doc_backend.ocr.application;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.List;
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
 *       unresolved result, not an error; never stored as a business value;</li>
 *   <li>{@code INVALID} — the answer is not a single clean value (an internal
 *       newline, an unmatched quote, a field label, prose, a list marker, an
 *       "A or B" alternative, no letter, a disallowed character, ...). The
 *       workflow treats this as a failed attempt and retries.</li>
 * </ul>
 *
 * <p>Names must be a single line of letters, spaces, apostrophes, hyphens and
 * single-letter initials (a letter optionally followed by a period, so
 * {@code A. K. Tan} is valid) and contain at least one Unicode letter; there is
 * no word-count cap (a legitimate multi-word name is accepted). Leading/trailing
 * whitespace (including a trailing newline) is trimmed first; an <em>internal</em>
 * newline, ellipses, and sentence-ending periods remain invalid. For the
 * policyholder only, a trailing bracketed identification number (e.g.
 * {@code (S1234567A)} or {@code [S1234567A]}) is removed, and blank, sentinel,
 * label, ambiguity, and name validation are then re-run on the trimmed result, so
 * {@code "UNKNOWN (123)"} is an unresolved token, never a name. An answer that
 * carries a known label/prose prefix (e.g. {@code Policyowner Name ...},
 * {@code The name is ...}) or a colon is rejected as prose, not a bare name; a
 * legitimate surname that merely contains a label-like word ({@code Masamune
 * Date}) is still accepted.
 */
public final class FieldAnswerParser {

	private static final int MAX_NAME_LENGTH = 256;

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

	/**
	 * Known label/prose prefixes that identify an answer as a field label or prose
	 * rather than a bare name. Detection is prefix-based (the answer starts with the
	 * phrase), so a legitimate surname that merely contains a label-like word (e.g.
	 * "Masamune Date") is still accepted. Colon-delimited labels are rejected
	 * separately by the colon check.
	 */
	private static final List<String> NAME_LABEL_PREFIXES = List.of(
			"policyowner name", "policyholder name", "policy owner name", "policy holder name",
			"consultant name", "financial consultant name",
			"the name is", "name is",
			"the policyholder is", "policyholder is",
			"the policyowner is", "policyowner is",
			"the consultant is", "consultant is",
			"the financial consultant is", "financial consultant is");

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
		// Trim leading/trailing whitespace (including a trailing newline) first; an
		// internal newline remains invalid.
		String s = raw.trim();
		if (hasVerticalWhitespace(s)) {
			return FieldAnswerParse.invalid();
		}
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
		if (field == ExtractionField.POLICY_CREATE_DATE) {
			return parseDate(s);
		}
		// For the policyholder only, remove a trailing bracketed identification
		// number, then re-run all validation (blank, sentinel, label, ambiguity,
		// name) on the trimmed result.
		if (field == ExtractionField.POLICYHOLDER_NAME) {
			s = TRAILING_ID.matcher(s).replaceFirst("").trim();
		}
		return validateName(s);
	}

	private static FieldAnswerParse validateName(String s) {
		if (s.isEmpty() || s.length() > MAX_NAME_LENGTH) {
			return FieldAnswerParse.invalid();
		}
		// Unresolved sentinels are checked after any policyholder ID removal, so
		// "UNKNOWN (123)" is a token, never a name.
		if (isUnknownToken(s)) {
			return FieldAnswerParse.unknown();
		}
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
		if (startsWithLabelPrefix(s)) {
			return FieldAnswerParse.invalid();
		}
		if (!containsUnicodeLetter(s) || !isNameLike(s)) {
			return FieldAnswerParse.invalid();
		}
		return FieldAnswerParse.resolved(s);
	}

	private static FieldAnswerParse parseDate(String s) {
		if (s.isEmpty()) {
			return FieldAnswerParse.invalid();
		}
		if (isUnknownToken(s)) {
			return FieldAnswerParse.unknown();
		}
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

	private static boolean startsWithLabelPrefix(String s) {
		String lower = s.toLowerCase(Locale.ROOT);
		for (String prefix : NAME_LABEL_PREFIXES) {
			if (lower.equals(prefix) || lower.startsWith(prefix + " ")) {
				return true;
			}
		}
		return false;
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

	/**
	 * Validates the name's character content. The name is split into whitespace
	 * tokens; each token is either an initial (a single letter, optionally followed
	 * by a period, e.g. "A" or "A.") or a regular word of letters, apostrophes and
	 * hyphens (no periods, digits or other punctuation). This permits name initials
	 * such as "A. K. Tan" while still rejecting ellipses ("explanation..."),
	 * sentence-ending periods, digits and other punctuation.
	 */
	private static boolean isNameLike(String s) {
		for (String token : s.split(" ")) {
			if (!isNameToken(token)) {
				return false;
			}
		}
		return true;
	}

	/** A valid name token: an initial ("A", "A.") or a word of letters/apostrophes/hyphens. */
	private static boolean isNameToken(String token) {
		if (token.isEmpty() || !Character.isLetter(token.charAt(0))) {
			return false;
		}
		// An initial: a single letter, optionally followed by a period.
		if (token.length() <= 2) {
			if (token.length() == 1) {
				return true;
			}
			return token.charAt(1) == '.';
		}
		// A regular word: letters, apostrophes and hyphens only (no periods/digits).
		for (int i = 1; i < token.length(); i++) {
			char c = token.charAt(i);
			if (!Character.isLetter(c) && c != '\'' && c != '\u2019'
					&& c != '-' && c != '\u2010' && c != '\u2011' && c != '\u2013') {
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
