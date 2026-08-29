package horse.sumomo.pos_doc_backend.persistence.entity;

import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import horse.sumomo.pos_doc_backend.persistence.model.DocumentProcessingStatus;
import horse.sumomo.pos_doc_backend.persistence.model.DocumentType;

/**
 * One document (PDF) extracted from a POS record's source archive.
 *
 * <p>All columns are required; {@code sequenceNumber} must be non-negative.
 * Each storage object may be attached to at most one document row (database
 * unique constraint on {@code storage_object_id}).
 */
@Entity
@Table(name = "pos_document")
public class PosDocumentEntity {

	@Id
	@Column(name = "id", nullable = false)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "pos_record_id", nullable = false)
	private PosRecordEntity posRecord;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "storage_object_id", nullable = false, unique = true)
	private StorageObjectEntity storageObject;

	@Column(name = "sequence_number", nullable = false)
	private long sequenceNumber;

	@Enumerated(EnumType.STRING)
	@Column(name = "document_type", nullable = false)
	private DocumentType documentType;

	@Enumerated(EnumType.STRING)
	@Column(name = "processing_status", nullable = false)
	private DocumentProcessingStatus processingStatus;

	protected PosDocumentEntity() {
		// JPA only
	}

	public PosDocumentEntity(UUID id, PosRecordEntity posRecord, StorageObjectEntity storageObject,
			long sequenceNumber, DocumentType documentType, DocumentProcessingStatus processingStatus) {
		if (id == null) {
			throw new IllegalArgumentException("id must not be null");
		}
		if (posRecord == null) {
			throw new IllegalArgumentException("posRecord must not be null");
		}
		if (storageObject == null) {
			throw new IllegalArgumentException("storageObject must not be null");
		}
		if (sequenceNumber < 0) {
			throw new IllegalArgumentException("sequenceNumber must be >= 0");
		}
		if (documentType == null) {
			throw new IllegalArgumentException("documentType must not be null");
		}
		if (processingStatus == null) {
			throw new IllegalArgumentException("processingStatus must not be null");
		}
		this.id = id;
		this.posRecord = posRecord;
		this.storageObject = storageObject;
		this.sequenceNumber = sequenceNumber;
		this.documentType = documentType;
		this.processingStatus = processingStatus;
	}

	public void setProcessingStatus(DocumentProcessingStatus value) {
		if (value == null) {
			throw new IllegalArgumentException("processingStatus must not be null");
		}
		this.processingStatus = value;
	}

	public UUID getId() {
		return this.id;
	}

	public PosRecordEntity getPosRecord() {
		return this.posRecord;
	}

	public StorageObjectEntity getStorageObject() {
		return this.storageObject;
	}

	public long getSequenceNumber() {
		return this.sequenceNumber;
	}

	public DocumentType getDocumentType() {
		return this.documentType;
	}

	public DocumentProcessingStatus getProcessingStatus() {
		return this.processingStatus;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof PosDocumentEntity that)) {
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
		return "PosDocumentEntity[id=" + this.id + "]";
	}

}
