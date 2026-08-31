package horse.sumomo.pos_doc_backend.persistence.entity;

import java.time.Instant;
import java.time.LocalDate;
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
import jakarta.persistence.Version;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import horse.sumomo.pos_doc_backend.persistence.model.PosRecordStatus;
import horse.sumomo.pos_doc_backend.persistence.normalization.MetadataNormalizer;

/**
 * One uploaded POS archive and its extracted display metadata.
 *
 * <p>eRef, policy number, and policyholder name are set through paired
 * mutators that always write the display and normalized columns together via
 * {@link MetadataNormalizer}; the normalized value is computed before either
 * field is assigned, so an update rejected by the normalizer leaves both
 * fields unchanged. There is no way to alter a normalized column
 * independently. Deletion is soft only: {@link #markDeleted(Instant)} stamps
 * {@code deletedAt} (and {@code updatedAt}) on first use and is a no-op when
 * the record is already deleted. It never touches related storage objects,
 * documents, or jobs.
 */
@Entity
@Table(name = "pos_record")
public class PosRecordEntity {

	@Id
	@Column(name = "id", nullable = false)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "source_archive_id", nullable = false, unique = true)
	private StorageObjectEntity sourceArchive;

	@Column(name = "eref_number")
	private String erefNumber;

	@Column(name = "eref_number_normalized")
	private String erefNumberNormalized;

	@Column(name = "policy_number")
	private String policyNumber;

	@Column(name = "policy_number_normalized")
	private String policyNumberNormalized;

	@Column(name = "policyholder_name")
	private String policyholderName;

	@Column(name = "policyholder_name_normalized")
	private String policyholderNameNormalized;

	@Column(name = "consultant_name")
	private String consultantName;

	@Column(name = "policy_create_date")
	private LocalDate policyCreateDate;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	private PosRecordStatus status;

	@Column(name = "uploaded_by", nullable = false)
	private String uploadedBy;

	@Column(name = "uploaded_at_epoch_ms", nullable = false)
	private Instant uploadedAt;

	@Column(name = "updated_at_epoch_ms", nullable = false)
	private Instant updatedAt;

	@Column(name = "deleted_at_epoch_ms")
	private Instant deletedAt;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected PosRecordEntity() {
		// JPA only
	}

	public PosRecordEntity(UUID id, StorageObjectEntity sourceArchive, PosRecordStatus status, String uploadedBy,
			Instant uploadedAt, Instant updatedAt) {
		if (id == null) {
			throw new IllegalArgumentException("id must not be null");
		}
		if (sourceArchive == null) {
			throw new IllegalArgumentException("sourceArchive must not be null");
		}
		if (status == null) {
			throw new IllegalArgumentException("status must not be null");
		}
		if (uploadedBy == null || uploadedBy.isBlank()) {
			throw new IllegalArgumentException("uploadedBy must not be blank");
		}
		if (uploadedAt == null) {
			throw new IllegalArgumentException("uploadedAt must not be null");
		}
		if (updatedAt == null) {
			throw new IllegalArgumentException("updatedAt must not be null");
		}
		this.id = id;
		this.sourceArchive = sourceArchive;
		this.status = status;
		this.uploadedBy = uploadedBy;
		this.uploadedAt = uploadedAt;
		this.updatedAt = updatedAt;
		this.version = 0;
	}

	public void setErefNumber(String value) {
		String normalized = MetadataNormalizer.normalizeIdentifier(value);
		this.erefNumber = value;
		this.erefNumberNormalized = normalized;
	}

	public void setPolicyNumber(String value) {
		String normalized = MetadataNormalizer.normalizeIdentifier(value);
		this.policyNumber = value;
		this.policyNumberNormalized = normalized;
	}

	public void setPolicyholderName(String value) {
		String normalized = MetadataNormalizer.normalizeName(value);
		this.policyholderName = value;
		this.policyholderNameNormalized = normalized;
	}

	public void setConsultantName(String value) {
		this.consultantName = value;
	}

	public void setPolicyCreateDate(LocalDate value) {
		this.policyCreateDate = value;
	}

	public void setStatus(PosRecordStatus value) {
		if (value == null) {
			throw new IllegalArgumentException("status must not be null");
		}
		this.status = value;
	}

	public void setUpdatedAt(Instant value) {
		this.updatedAt = value;
	}

	/**
	 * Marks the record as actively being processed. Stamps {@code updatedAt}
	 * so observers see the transition. Does not touch soft-delete state.
	 */
	public void markProcessing(Instant now) {
		Objects.requireNonNull(now, "processing instant must not be null");
		this.status = PosRecordStatus.PROCESSING;
		this.updatedAt = now;
	}

	/**
	 * Soft-deletes this record. Requires a non-null deletion instant, is
	 * idempotent, and sets {@code deletedAt} and {@code updatedAt} on the
	 * first call only. Related storage objects, documents, and jobs are left
	 * untouched.
	 */
	public void markDeleted(Instant deletedAt) {
		Objects.requireNonNull(deletedAt, "deletion instant must not be null");
		if (this.deletedAt == null) {
			this.deletedAt = deletedAt;
			this.updatedAt = deletedAt;
		}
	}

	public UUID getId() {
		return this.id;
	}

	public StorageObjectEntity getSourceArchive() {
		return this.sourceArchive;
	}

	public String getErefNumber() {
		return this.erefNumber;
	}

	public String getErefNumberNormalized() {
		return this.erefNumberNormalized;
	}

	public String getPolicyNumber() {
		return this.policyNumber;
	}

	public String getPolicyNumberNormalized() {
		return this.policyNumberNormalized;
	}

	public String getPolicyholderName() {
		return this.policyholderName;
	}

	public String getPolicyholderNameNormalized() {
		return this.policyholderNameNormalized;
	}

	public String getConsultantName() {
		return this.consultantName;
	}

	public LocalDate getPolicyCreateDate() {
		return this.policyCreateDate;
	}

	public PosRecordStatus getStatus() {
		return this.status;
	}

	public String getUploadedBy() {
		return this.uploadedBy;
	}

	public Instant getUploadedAt() {
		return this.uploadedAt;
	}

	public Instant getUpdatedAt() {
		return this.updatedAt;
	}

	public Instant getDeletedAt() {
		return this.deletedAt;
	}

	public long getVersion() {
		return this.version;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof PosRecordEntity that)) {
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
		return "PosRecordEntity[id=" + this.id + ", status=" + this.status + "]";
	}

}
