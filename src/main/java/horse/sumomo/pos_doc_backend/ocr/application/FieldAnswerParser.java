package horse.sumomo.pos_doc_backend.ocr.application;

import java.time.LocalDate;
import java.time.DateTimeException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import horse.sumomo.pos_doc_backend.persistence.model.ExtractionField;

/**
 * Pure normalization and validation for one structured field answer.
 *
 * <p>No I/O, no randomness, and no PII is written to logs. The parser:
 * <ol>
 *   <li>normalizes raw model text: trims, removes a leading or wrapping pair of
 *       quotation marks, and collapses consecutive whitespace to single spaces;</li>
 *   <li>treats the normalized tokens {@code UNKNOWN}, {@code NONE}, and {@code N/A}
 *       (case-insensitive) as a valid unresolved result ({@link FieldAnswerParse.ParseKind#UNKNOWN});</li>
 *   <li>validates names: nonblank after normalization, at most 256 characters, no
 *       NUL, and no ASCII control characters ({@code U+0000–U+001F, U+007F}); and</li>
 *   <li>validates dates: strict {@code dd-MMM-yyyy} with English month
 *       abbreviations (case-insensitive), a valid calendar date, canonicalized to
 *       ISO {@code yyyy-MM-dd}. Other date formats are not accepted.</li>
 * </ol>
 */
public final class FieldAnswerParser {

	private static final int MAX_NAME_LENGTH = 256;

	/** Strict shape for the submission date: two-digit day, three-letter month, four-digit year. */
	private static final Pattern DATE_PATTERN = Pattern.compile("^(\\d{2})-([A-Za-z]{3})-(\\d{4})$");

	private static final String UNKNOWN = "UNKNOWN";
	private static final String NONE = "NONE";
	private static final String NA = "N/A";

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
		String norm = normalize(raw);
		if (isUnknownToken(norm)) {
			return FieldAnswerParse.unknown();
		}
		if (field == ExtractionField.POLICY_CREATE_DATE) {
			return parseDate(norm);
		}
		return parseName(norm);
	}

	/**
	 * Trims, removes a leading or wrapping pair of quotation marks, and collapses
	 * consecutive whitespace to single spaces.
	 *
	 * @param raw the raw text (may be {@code null})
	 * @return the normalized text; never {@code null}
	 */
	static String normalize(String raw) {
		if (raw == null) {
			return "";
		}
		String s = raw.trim();
		s = stripLeadingOrWrappingQuotes(s);
		s = s.replaceAll("\\s+", " ").trim();
		return s;
	}

	private static String stripLeadingOrWrappingQuotes(String s) {
		if (s.isEmpty()) {
			return s;
		}
		char first = s.charAt(0);
		if (first == '"' || first == '\'' || first == '`') {
			char last = s.charAt(s.length() - 1);
			if (s.length() >= 2 && last == first) {
				return s.substring(1, s.length() - 1);
			}
			return s.substring(1);
		}
		return s;
	}

	private static boolean isUnknownToken(String norm) {
		return norm.equalsIgnoreCase(UNKNOWN)
				|| norm.equalsIgnoreCase(NONE)
				|| norm.equalsIgnoreCase(NA);
	}

	private static FieldAnswerParse parseName(String norm) {
		if (norm.isEmpty()) {
			return FieldAnswerParse.invalid();
		}
		if (norm.length() > MAX_NAME_LENGTH) {
			return FieldAnswerParse.invalid();
		}
		if (hasAsciiControlCharacter(norm)) {
			return FieldAnswerParse.invalid();
		}
		return FieldAnswerParse.resolved(norm);
	}

	private static FieldAnswerParse parseDate(String norm) {
		if (norm.isEmpty()) {
			return FieldAnswerParse.invalid();
		}
		Matcher m = DATE_PATTERN.matcher(norm);
		if (!m.matches()) {
			return FieldAnswerParse.invalid();
		}
		String monthAbbr = m.group(2).toUpperCase(Locale.ROOT);
		int month = monthNumber(monthAbbr);
		if (month == 0) {
			return FieldAnswerParse.invalid();
		}
		int day = Integer.parseInt(m.group(1));
		int year = Integer.parseInt(m.group(3));
		try {
			LocalDate date = LocalDate.of(year, month, day);
			return FieldAnswerParse.resolved(date.toString());
		}
		catch (DateTimeException e) {
			return FieldAnswerParse.invalid();
		}
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

	private static boolean hasAsciiControlCharacter(String s) {
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c <= 0x1F || c == 0x7F) {
				return true;
			}
		}
		return false;
	}

}
