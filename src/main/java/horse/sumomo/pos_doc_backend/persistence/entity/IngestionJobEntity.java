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

	/**
	 * Begins one processing attempt: transitions the status to
	 * {@link JobStatus#RUNNING}, increments the attempt count exactly once,
	 * and stamps {@code startedAt}. Calling again during the same attempt
	 * does not double-increment; the counter increases only on the first
	 * invocation for this attempt.
	 */
	public void startAttempt(Instant now) {
		Objects.requireNonNull(now, "attempt instant must not be null");
		this.status = JobStatus.RUNNING;
		this.attemptCount = this.attemptCount + 1L;
		if (this.startedAt == null) {
			this.startedAt = now;
		}
	}

	/**
	 * Marks the job completed and stamps {@code completedAt} on first call.
	 * Subsequent calls preserve the first completion instant.
	 */
	public void complete(Instant now) {
		Objects.requireNonNull(now, "completion instant must not be null");
		this.status = JobStatus.COMPLETED;
		if (this.completedAt == null) {
			this.completedAt = now;
		}
	}

	/**
	 * Records a terminal failure with sanitized error fields. The status
	 * transitions to {@link JobStatus#FAILED} and {@code completedAt} is
	 * stamped if not already set. Error message must contain no PII; the
	 * upstream listener sanitizes before calling this mutator.
	 */
	public void fail(Instant now, String errorCode, String safeErrorMessage) {
		Objects.requireNonNull(now, "failure instant must not be null");
		Objects.requireNonNull(errorCode, "errorCode must not be null");
		if (errorCode.isBlank()) {
			throw new IllegalArgumentException("errorCode must not be blank");
		}
		this.status = JobStatus.FAILED;
		this.errorCode = errorCode;
		this.errorMessage = safeErrorMessage;
		if (this.completedAt == null) {
			this.completedAt = now;
		}
	}

	/**
	 * Records a transient failure for the current attempt. Status
	 * transitions to {@link JobStatus#RETRY_SCHEDULED}; the attempt counter
	 * is left unchanged (the previous {@code startAttempt} call already
	 * recorded it). The next listener attempt will call
	 * {@link #startAttempt(Instant)} again, bumping the counter.
	 */
	public void markRetry(Instant now, String errorCode, String safeErrorMessage) {
		Objects.requireNonNull(now, "retry instant must not be null");
		Objects.requireNonNull(errorCode, "errorCode must not be null");
		if (errorCode.isBlank()) {
			throw new IllegalArgumentException("errorCode must not be blank");
		}
		this.status = JobStatus.RETRY_SCHEDULED;
		this.errorCode = errorCode;
		this.errorMessage = safeErrorMessage;
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
