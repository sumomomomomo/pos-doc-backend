package horse.sumomo.pos_doc_backend.ocr.model;

import java.util.UUID;

/**
 * Immutable, ephemeral OCR result.
 *
 * <p>Contains the recognized text and safe model metadata. The text is
 * ephemeral: it must not be persisted, logged, or included in
 * {@link #toString()}.
 *
 * <p>{@link #toString()} omits {@code text} entirely and reports only the
 * document ID, model, finish reason, prompt version, and character count.
 */
public final class OcrResult {

	private final UUID documentId;
	private final String text;
	private final String model;
	private final String finishReason;
	private final int promptVersion;

	public OcrResult(UUID documentId, String text, String model, String finishReason, int promptVersion) {
		if (documentId == null) {
			throw new IllegalArgumentException("documentId must not be null");
		}
		if (text == null) {
			throw new IllegalArgumentException("text must not be null");
		}
		if (text.isBlank()) {
			throw new IllegalArgumentException("text must not be blank");
		}
		if (model == null || model.isBlank()) {
			throw new IllegalArgumentException("model must not be blank");
		}
		if (finishReason == null || finishReason.isBlank()) {
			throw new IllegalArgumentException("finishReason must not be blank");
		}
		this.documentId = documentId;
		this.text = text;
		this.model = model;
		this.finishReason = finishReason;
		this.promptVersion = promptVersion;
	}

	public UUID documentId() {
		return this.documentId;
	}

	public String text() {
		return this.text;
	}

	public String model() {
		return this.model;
	}

	public String finishReason() {
		return this.finishReason;
	}

	public int promptVersion() {
		return this.promptVersion;
	}

	@Override
	public String toString() {
		return "OcrResult[documentId=" + this.documentId + ", model=" + this.model
				+ ", finishReason=" + this.finishReason + ", promptVersion=" + this.promptVersion
				+ ", textLength=" + this.text.length() + "]";
	}

}
