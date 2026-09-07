package horse.sumomo.pos_doc_backend.persistence.entity;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One durable OCR result for a document at a specific prompt version.
 *
 * <p>The composite primary key is {@link DocumentOcrResultId}
 * ({@code document_id, prompt_version}). The entity enforces the same
 * non-null, nonblank, length, prompt-version, and character-count
 * invariants as the database check constraints before persistence.
 *
 * <p><strong>PII / OCR-text safety:</strong> {@link #toString()},
 * {@link #equals(Object)}, and {@link #hashCode()} never include the OCR
 * text or the lazy {@link PosDocumentEntity} association. The OCR text is
 * exposed only through {@link #getOcrText()} for internal application
 * code that needs to persist or compare it.
 */
@Entity
@Table(name = "document_ocr_result")
public class DocumentOcrResultEntity {

	static final int MAX_OCR_TEXT_LENGTH = 1_000_000;

	@EmbeddedId
	private DocumentOcrResultId id;

	@Column(name = "ocr_text", nullable = false)
	private String ocrText;

	@Column(name = "model", nullable = false)
	private String model;

	@Column(name = "finish_reason", nullable = false)
	private String finishReason;

	@Column(name = "character_count", nullable = false)
	private int characterCount;

	@Column(name = "completed_at_epoch_ms", nullable = false)
	private Instant completedAt;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "document_id", insertable = false, updatable = false)
	private PosDocumentEntity document;

	protected DocumentOcrResultEntity() {
		// JPA only
	}

	public DocumentOcrResultEntity(DocumentOcrResultId id, String ocrText, String model, String finishReason,
			Instant completedAt) {
		if (id == null) {
			throw new IllegalArgumentException("id must not be null");
		}
		if (ocrText == null) {
			throw new IllegalArgumentException("ocrText must not be null");
		}
		if (ocrText.isBlank()) {
			throw new IllegalArgumentException("ocrText must not be blank");
		}
		if (ocrText.length() > MAX_OCR_TEXT_LENGTH) {
			throw new IllegalArgumentException("ocrText must be at most " + MAX_OCR_TEXT_LENGTH + " characters");
		}
		if (model == null || model.isBlank()) {
			throw new IllegalArgumentException("model must not be blank");
		}
		if (finishReason == null || finishReason.isBlank()) {
			throw new IllegalArgumentException("finishReason must not be blank");
		}
		if (completedAt == null) {
			throw new IllegalArgumentException("completedAt must not be null");
		}
		this.id = id;
		this.ocrText = ocrText;
		this.model = model;
		this.finishReason = finishReason;
		this.characterCount = ocrText.length();
		this.completedAt = completedAt;
	}

	public DocumentOcrResultId getId() {
		return this.id;
	}

	public String getOcrText() {
		return this.ocrText;
	}

	public String getModel() {
		return this.model;
	}

	public String getFinishReason() {
		return this.finishReason;
	}

	public int getCharacterCount() {
		return this.characterCount;
	}

	public Instant getCompletedAt() {
		return this.completedAt;
	}

	public PosDocumentEntity getDocument() {
		return this.document;
	}

	/**
	 * Returns {@code true} when the safe metadata (model, finish reason,
	 * character count) matches the given values. Used by the workflow to
	 * treat an equivalent already-committed result as idempotent success
	 * without comparing OCR text.
	 */
	public boolean hasEquivalentMetadata(String model, String finishReason, int characterCount) {
		return this.model.equals(model) && this.finishReason.equals(finishReason)
				&& this.characterCount == characterCount;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof DocumentOcrResultEntity that)) {
			return false;
		}
		return this.id != null && this.id.equals(that.id);
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(this.id);
	}

	@Override
	public String toString() {
		return "DocumentOcrResultEntity[id=" + this.id + ", model=" + this.model
				+ ", finishReason=" + this.finishReason + ", characterCount=" + this.characterCount
				+ ", completedAt=" + this.completedAt + "]";
	}

}
