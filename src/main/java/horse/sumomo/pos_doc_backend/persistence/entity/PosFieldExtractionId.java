package horse.sumomo.pos_doc_backend.persistence.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Composite primary key for {@link PosFieldExtractionEntity}: the document
 * UUID, the field name, and the prompt version.
 *
 * <p>This is a value object: equality and hashing are based on all three
 * components. {@code fieldName} is the {@code ExtractionField} enum name. It
 * never carries a value or error code, so {@link #toString()} is PII-safe.
 */
@Embeddable
public class PosFieldExtractionId implements Serializable {

	private static final long serialVersionUID = 1L;

	@Column(name = "document_id", nullable = false)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	private UUID documentId;

	@Column(name = "field_name", nullable = false)
	private String fieldName;

	@Column(name = "prompt_version", nullable = false)
	private int promptVersion;

	protected PosFieldExtractionId() {
		// JPA only
	}

	public PosFieldExtractionId(UUID documentId, String fieldName, int promptVersion) {
		if (documentId == null) {
			throw new IllegalArgumentException("documentId must not be null");
		}
		if (fieldName == null || fieldName.isBlank()) {
			throw new IllegalArgumentException("fieldName must not be blank");
		}
		if (promptVersion <= 0) {
			throw new IllegalArgumentException("promptVersion must be > 0");
		}
		this.documentId = documentId;
		this.fieldName = fieldName;
		this.promptVersion = promptVersion;
	}

	public UUID getDocumentId() {
		return this.documentId;
	}

	public String getFieldName() {
		return this.fieldName;
	}

	public int getPromptVersion() {
		return this.promptVersion;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof PosFieldExtractionId that)) {
			return false;
		}
		return this.promptVersion == that.promptVersion
				&& this.documentId != null && this.documentId.equals(that.documentId)
				&& this.fieldName != null && this.fieldName.equals(that.fieldName);
	}

	@Override
	public int hashCode() {
		return Objects.hash(this.documentId, this.fieldName, this.promptVersion);
	}

	@Override
	public String toString() {
		return "PosFieldExtractionId[documentId=" + this.documentId + ", fieldName=" + this.fieldName
				+ ", promptVersion=" + this.promptVersion + "]";
	}

}
