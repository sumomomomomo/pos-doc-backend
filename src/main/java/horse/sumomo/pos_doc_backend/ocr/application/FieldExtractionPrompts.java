package horse.sumomo.pos_doc_backend.ocr.application;

import java.util.Objects;

import horse.sumomo.pos_doc_backend.persistence.model.ExtractionField;

/**
 * The three exact structured-extraction field prompts and their prompt version.
 *
 * <p>The structured workflow uses prompt version {@link #PROMPT_VERSION} ({@code 2});
 * version {@code 1} belongs to the old generic full-page OCR workflow. The prompts are
 * named constants defined in exactly one place and are sent verbatim — never
 * concatenated or reworded — one prompt per field per request.
 *
 * <p>Each prompt instructs the model to return the single value or the literal token
 * {@code UNKNOWN} when the field is absent, unreadable, or ambiguous.
 */
public final class FieldExtractionPrompts {

	/** Prompt version for the structured field-extraction workflow. */
	public static final int PROMPT_VERSION = 2;

	public static final String POLICYHOLDER_NAME =
			"Return only the value of the Policyowner Name shown in this document. "
					+ "Exclude any bracketed identification number that follows the name. "
					+ "Example output: Charlie Henry. "
					+ "If the field is absent, unreadable, or ambiguous, return exactly UNKNOWN.";

	public static final String CONSULTANT_NAME =
			"Return only the Financial Consultant Name shown in brackets in this document. "
					+ "Do not include brackets, labels, identification numbers, or any other text. "
					+ "Example output: John Davidson. "
					+ "If the field is absent, unreadable, or ambiguous, return exactly UNKNOWN.";

	public static final String POLICY_CREATE_DATE =
			"Return only the Submission Date shown in this document, formatted exactly as dd-MMM-yyyy "
					+ "using English month abbreviations. Example output: 26-Jul-2026. "
					+ "If the field is absent, unreadable, or ambiguous, return exactly UNKNOWN.";

	private FieldExtractionPrompts() {
	}

	/**
	 * Returns the exact prompt for the given field.
	 *
	 * @param field the field; must not be null
	 * @return the verbatim prompt string
	 */
	public static String promptFor(ExtractionField field) {
		Objects.requireNonNull(field, "field must not be null");
		return switch (field) {
			case POLICYHOLDER_NAME -> POLICYHOLDER_NAME;
			case CONSULTANT_NAME -> CONSULTANT_NAME;
			case POLICY_CREATE_DATE -> POLICY_CREATE_DATE;
		};
	}

}
