package horse.sumomo.pos_doc_backend.persistence.entity;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Metadata for one object stored in MinIO.
 *
 * <p>Input is validated before the state can exist on the entity: blank
 * keys/filenames/content types, negative sizes, malformed SHA-256 values,
 * and missing timestamps are all rejected with
 * {@link IllegalArgumentException}. SHA-256 is stored lowercase. The
 * {@link #toString()} deliberately excludes the object key, filename, and
 * digest so it is safe to log.
 */
@Entity
@Table(name = "storage_object")
public class StorageObjectEntity {

	private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-fA-F]{64}");

	@Id
	@Column(name = "id", nullable = false)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	private UUID id;

	@Column(name = "object_key", nullable = false, unique = true)
	private String objectKey;

	@Column(name = "original_filename", nullable = false)
	private String originalFilename;

	@Column(name = "content_type", nullable = false)
	private String contentType;

	@Column(name = "byte_size", nullable = false)
	private long byteSize;

	@Column(name = "sha256", nullable = false)
	private String sha256;

	@Column(name = "created_at_epoch_ms", nullable = false)
	private Instant createdAt;

	protected StorageObjectEntity() {
		// JPA only
	}

	public StorageObjectEntity(UUID id, String objectKey, String originalFilename, String contentType,
			long byteSize, String sha256, Instant createdAt) {
		if (id == null) {
			throw new IllegalArgumentException("id must not be null");
		}
		if (objectKey == null || objectKey.isBlank()) {
			throw new IllegalArgumentException("objectKey must not be blank");
		}
		if (originalFilename == null || originalFilename.isBlank()) {
			throw new IllegalArgumentException("originalFilename must not be blank");
		}
		if (contentType == null || contentType.isBlank()) {
			throw new IllegalArgumentException("contentType must not be blank");
		}
		if (byteSize < 0) {
			throw new IllegalArgumentException("byteSize must be >= 0");
		}
		if (sha256 == null || !SHA256_PATTERN.matcher(sha256).matches()) {
			throw new IllegalArgumentException("sha256 must contain exactly 64 hexadecimal characters");
		}
		if (createdAt == null) {
			throw new IllegalArgumentException("createdAt must not be null");
		}
		this.id = id;
		this.objectKey = objectKey;
		this.originalFilename = originalFilename;
		this.contentType = contentType;
		this.byteSize = byteSize;
		this.sha256 = sha256.toLowerCase(Locale.ROOT);
		this.createdAt = createdAt;
	}

	public UUID getId() {
		return this.id;
	}

	public String getObjectKey() {
		return this.objectKey;
	}

	public String getOriginalFilename() {
		return this.originalFilename;
	}

	public String getContentType() {
		return this.contentType;
	}

	public long getByteSize() {
		return this.byteSize;
	}

	public String getSha256() {
		return this.sha256;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof StorageObjectEntity that)) {
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
		return "StorageObjectEntity[id=" + this.id + "]";
	}

}
