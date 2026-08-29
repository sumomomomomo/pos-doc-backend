package horse.sumomo.pos_doc_backend.persistence.entity;

import java.time.Instant;
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

import horse.sumomo.pos_doc_backend.persistence.model.JobStatus;

/**
 * One unit of ingestion work for a POS record.
 *
 * <p>{@code attemptCount} must be non-negative. Error messages are sanitized
 * upstream in a later task and are intentionally not logged here; the
 * {@link #toString()} therefore excludes {@code errorCode} and
 * {@code errorMessage}.
 */
@Entity
@Table(name = "ingestion_job")
public class IngestionJobEntity {

	@Id
	@Column(name = "id", nullable = false)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "pos_record_id", nullable = false)
	private PosRecordEntity posRecord;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	private JobStatus status;

	@Column(name = "attempt_count", nullable = false)
	private long attemptCount;

	@Column(name = "error_code")
	private String errorCode;

	@Column(name = "error_message")
	private String errorMessage;

	@Column(name = "created_at_epoch_ms", nullable = false)
	private Instant createdAt;

	@Column(name = "started_at_epoch_ms")
	private Instant startedAt;

	@Column(name = "completed_at_epoch_ms")
	private Instant completedAt;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected IngestionJobEntity() {
		// JPA only
	}

	public IngestionJobEntity(UUID id, PosRecordEntity posRecord, JobStatus status, long attemptCount,
			Instant createdAt) {
		if (id == null) {
			throw new IllegalArgumentException("id must not be null");
		}
		if (posRecord == null) {
			throw new IllegalArgumentException("posRecord must not be null");
		}
		if (status == null) {
			throw new IllegalArgumentException("status must not be null");
		}
		if (attemptCount < 0) {
			throw new IllegalArgumentException("attemptCount must be >= 0");
		}
		if (createdAt == null) {
			throw new IllegalArgumentException("createdAt must not be null");
		}
		this.id = id;
		this.posRecord = posRecord;
		this.status = status;
		this.attemptCount = attemptCount;
		this.createdAt = createdAt;
		this.version = 0;
	}

	public void setStatus(JobStatus value) {
		if (value == null) {
			throw new IllegalArgumentException("status must not be null");
		}
		this.status = value;
	}

	public void setAttemptCount(long value) {
		if (value < 0) {
			throw new IllegalArgumentException("attemptCount must be >= 0");
		}
		this.attemptCount = value;
	}

	public void setErrorCode(String value) {
		this.errorCode = value;
	}

	public void setErrorMessage(String value) {
		this.errorMessage = value;
	}

	public void setStartedAt(Instant value) {
		this.startedAt = value;
	}

	public void setCompletedAt(Instant value) {
		this.completedAt = value;
	}

	public UUID getId() {
		return this.id;
	}

	public PosRecordEntity getPosRecord() {
		return this.posRecord;
	}

	public JobStatus getStatus() {
		return this.status;
	}

	public long getAttemptCount() {
		return this.attemptCount;
	}

	public String getErrorCode() {
		return this.errorCode;
	}

	public String getErrorMessage() {
		return this.errorMessage;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

	public Instant getStartedAt() {
		return this.startedAt;
	}

	public Instant getCompletedAt() {
		return this.completedAt;
	}

	public long getVersion() {
		return this.version;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof IngestionJobEntity that)) {
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
		return "IngestionJobEntity[id=" + this.id + ", status=" + this.status + "]";
	}

}
