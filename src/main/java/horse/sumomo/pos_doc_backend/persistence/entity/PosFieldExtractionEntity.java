package horse.sumomo.pos_doc_backend.persistence.entity;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import horse.sumomo.pos_doc_backend.persistence.model.ExtractionOutcome;

/**
 * One durable structured field-extraction outcome for a document at a specific
 * prompt version.
 *
 * <p>The composite primary key is {@link PosFieldExtractionId}
 * ({@code document_id, field_name, prompt_version}). {@link #valueText} holds
 * only the canonical validated value (a validated name or an ISO
 * {@code yyyy-MM-dd} date); the full model response, image, prompt, PDF
 * content, and response body are never stored. {@link #errorCode} is a stable
 * sanitized code with no PII or remote response content.
 *
 * <p>The constructor enforces the same outcome/value/error invariants as the
 * database check constraints before persistence:
 * <ul>
 *   <li>{@code RESOLVED} -> valueText present (nonblank), errorCode null</li>
 *   <li>{@code UNKNOWN}  -> valueText null, errorCode null</li>
 *   <li>{@code FAILED}   -> valueText null, errorCode present (nonblank)</li>
 * </ul>
 *
 * <p><strong>PII safety:</strong> {@link #toString()} and the identity methods
 * never include {@code valueText} or {@code errorCode}; they expose only the
 * key, outcome, model, and attempt count.
 */
@Entity
@Table(name = "pos_field_extraction")
public class PosFieldExtractionEntity {

	@EmbeddedId
	private PosFieldExtractionId id;

	@Enumerated(EnumType.STRING)
	@Column(name = "outcome", nullable = false)
	private ExtractionOutcome outcome;

	@Column(name = "value_text")
	private String valueText;

	@Column(name = "model", nullable = false)
	private String model;

	@Column(name = "finish_reason")
	private String finishReason;

	@Column(name = "attempt_count", nullable = false)
	private int attemptCount;

	@Column(name = "error_code")
	private String errorCode;

	@Column(name = "completed_at_epoch_ms", nullable = false)
	private Instant completedAt;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "document_id", insertable = false, updatable = false)
	private PosDocumentEntity document;

	protected PosFieldExtractionEntity() {
		// JPA only
	}

	public PosFieldExtractionEntity(PosFieldExtractionId id, ExtractionOutcome outcome, String valueText,
			String model, String finishReason, int attemptCount, String errorCode, Instant completedAt) {
		if (id == null) {
			throw new IllegalArgumentException("id must not be null");
		}
		if (outcome == null) {
			throw new IllegalArgumentException("outcome must not be null");
		}
		if (model == null || model.isBlank()) {
			throw new IllegalArgumentException("model must not be blank");
		}
		if (attemptCount < 1 || attemptCount > 3) {
			throw new IllegalArgumentException("attemptCount must be between 1 and 3");
		}
		if (completedAt == null) {
			throw new IllegalArgumentException("completedAt must not be null");
		}
		switch (outcome) {
			case RESOLVED -> {
				if (valueText == null || valueText.isBlank()) {
					throw new IllegalArgumentException("RESOLVED outcome requires a nonblank valueText");
				}
				if (errorCode != null) {
					throw new IllegalArgumentException("RESOLVED outcome requires a null errorCode");
				}
			}
			case UNKNOWN -> {
				if (valueText != null) {
					throw new IllegalArgumentException("UNKNOWN outcome requires a null valueText");
				}
				if (errorCode != null) {
					throw new IllegalArgumentException("UNKNOWN outcome requires a null errorCode");
				}
			}
			case FAILED -> {
				if (valueText != null) {
					throw new IllegalArgumentException("FAILED outcome requires a null valueText");
				}
				if (errorCode == null || errorCode.isBlank()) {
					throw new IllegalArgumentException("FAILED outcome requires a nonblank errorCode");
				}
			}
		}
		this.id = id;
		this.outcome = outcome;
		this.valueText = valueText;
		this.model = model.trim();
		this.finishReason = finishReason;
		this.attemptCount = attemptCount;
		this.errorCode = errorCode;
		this.completedAt = completedAt;
	}

	public PosFieldExtractionId getId() {
		return this.id;
	}

	public ExtractionOutcome getOutcome() {
		return this.outcome;
	}

	public String getValueText() {
		return this.valueText;
	}

	public String getModel() {
		return this.model;
	}

	public String getFinishReason() {
		return this.finishReason;
	}

	public int getAttemptCount() {
		return this.attemptCount;
	}

	public String getErrorCode() {
		return this.errorCode;
	}

	public Instant getCompletedAt() {
		return this.completedAt;
	}

	public PosDocumentEntity getDocument() {
		return this.document;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof PosFieldExtractionEntity that)) {
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
		return "PosFieldExtractionEntity[id=" + this.id + ", outcome=" + this.outcome + ", model=" + this.model
				+ ", attemptCount=" + this.attemptCount + "]";
	}

}
