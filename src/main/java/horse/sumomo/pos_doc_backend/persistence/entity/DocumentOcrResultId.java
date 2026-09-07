package horse.sumomo.pos_doc_backend.persistence.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Composite primary key for {@link DocumentOcrResultEntity}: the document
 * UUID and the prompt version.
 *
 * <p>This is a value object: equality and hashing are based on both
 * components. It never carries OCR text, so {@link #toString()} is safe.
 */
@Embeddable
public class DocumentOcrResultId implements Serializable {

	private static final long serialVersionUID = 1L;

	@Column(name = "document_id", nullable = false)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	private UUID documentId;

	@Column(name = "prompt_version", nullable = false)
	private int promptVersion;

	protected DocumentOcrResultId() {
		// JPA only
	}

	public DocumentOcrResultId(UUID documentId, int promptVersion) {
		if (documentId == null) {
			throw new IllegalArgumentException("documentId must not be null");
		}
		if (promptVersion <= 0) {
			throw new IllegalArgumentException("promptVersion must be > 0");
		}
		this.documentId = documentId;
		this.promptVersion = promptVersion;
	}

	public UUID getDocumentId() {
		return this.documentId;
	}

	public int getPromptVersion() {
		return this.promptVersion;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof DocumentOcrResultId that)) {
			return false;
		}
		return this.promptVersion == that.promptVersion
				&& this.documentId != null && this.documentId.equals(that.documentId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(this.documentId, this.promptVersion);
	}

	@Override
	public String toString() {
		return "DocumentOcrResultId[documentId=" + this.documentId + ", promptVersion=" + this.promptVersion + "]";
	}

}
