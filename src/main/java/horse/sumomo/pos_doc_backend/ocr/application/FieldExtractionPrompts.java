package horse.sumomo.pos_doc_backend.ocr.application;

import java.util.Objects;

import horse.sumomo.pos_doc_backend.persistence.model.ExtractionField;

/**
 * The three exact structured-extraction field prompts and their prompt version.
 *
 * <p>The structured workflow uses prompt version {@link #PROMPT_VERSION} ({@code 3});
 * version {@code 1} belongs to the old generic full-page OCR workflow, and version
 * {@code 2} was the dots.mocr contract. This value versions the <em>complete</em>
 * extraction contract: the prompt text, the model family, and the sampling behavior.
 * It was incremented from {@code 2} to {@code 3} when the inference contract moved
 * from dots.mocr to Qwen 3.5 8B (temperature 0.7, top-p 0.8, top-k 20, min-p 0.0,
 * presence-penalty 1.5, repeat-penalty 1.0) even though the three prompt texts are
 * unchanged. The prompts are named constants defined in exactly one place and are
 * sent verbatim — never concatenated or reworded — one prompt per field per request.
 *
 * <p>Each prompt instructs the model to return the single value or the literal token
 * {@code UNKNOWN} when the field is absent, unreadable, or ambiguous.
 */
public final class FieldExtractionPrompts {

	/**
	 * Version of the complete extraction contract (prompt text + model family +
	 * sampling behavior). Bumped from 2 to 3 for the Qwen 3.5 8B contract.
	 */
	public static final int PROMPT_VERSION = 3;

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
