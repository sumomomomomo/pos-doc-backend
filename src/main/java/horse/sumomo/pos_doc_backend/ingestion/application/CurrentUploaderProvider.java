package horse.sumomo.pos_doc_backend.ingestion.application;

import org.springframework.stereotype.Component;

/**
 * Supplies the uploader subject for new POS records.
 *
 * <p>Authentication is not implemented yet, so every upload is attributed to
 * the fixed placeholder {@link #PLACEHOLDER_SUBJECT}. The value must never
 * be taken from the request. A future authentication task replaces this
 * provider's implementation; nothing else in the intake path references the
 * constant.
 */
@Component
public class CurrentUploaderProvider {

	public static final String PLACEHOLDER_SUBJECT = "AUTH_NOT_IMPLEMENTED";

	public String currentUploader() {
		return PLACEHOLDER_SUBJECT;
	}

}
