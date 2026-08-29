package horse.sumomo.pos_doc_backend.ingestion.api;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed, validated limits for secure ZIP intake.
 *
 * <p>Bound from {@code app.upload.*}. All byte and count limits must be
 * positive, a single entry may not exceed the total uncompressed budget, and
 * the compressed budget is pinned to exactly 10 MiB until the OpenAPI
 * contract changes it. {@link #toString()} exposes no values that would not
 * be safe to log.
 */
@ConfigurationProperties(prefix = "app.upload")
public final class UploadLimitsProperties {

	static final long TEN_MIB = 10L * 1024L * 1024L;

	private final long maxCompressedBytes;
	private final long maxUncompressedBytes;
	private final long maxEntryBytes;
	private final int maxFileEntries;
	private final int maxCompressionRatio;

	public UploadLimitsProperties(long maxCompressedBytes, long maxUncompressedBytes, long maxEntryBytes,
			int maxFileEntries, int maxCompressionRatio) {
		if (maxCompressedBytes != TEN_MIB) {
			throw new IllegalArgumentException("app.upload.max-compressed-bytes must be exactly 10 MiB (10485760)");
		}
		if (maxUncompressedBytes <= 0) {
			throw new IllegalArgumentException("app.upload.max-uncompressed-bytes must be positive");
		}
		if (maxEntryBytes <= 0) {
			throw new IllegalArgumentException("app.upload.max-entry-bytes must be positive");
		}
		if (maxEntryBytes > maxUncompressedBytes) {
			throw new IllegalArgumentException("app.upload.max-entry-bytes must be <= app.upload.max-uncompressed-bytes");
		}
		if (maxFileEntries <= 0) {
			throw new IllegalArgumentException("app.upload.max-file-entries must be positive");
		}
		if (maxCompressionRatio <= 0) {
			throw new IllegalArgumentException("app.upload.max-compression-ratio must be positive");
		}
		this.maxCompressedBytes = maxCompressedBytes;
		this.maxUncompressedBytes = maxUncompressedBytes;
		this.maxEntryBytes = maxEntryBytes;
		this.maxFileEntries = maxFileEntries;
		this.maxCompressionRatio = maxCompressionRatio;
	}

	public long maxCompressedBytes() {
		return this.maxCompressedBytes;
	}

	public long maxUncompressedBytes() {
		return this.maxUncompressedBytes;
	}

	public long maxEntryBytes() {
		return this.maxEntryBytes;
	}

	public int maxFileEntries() {
		return this.maxFileEntries;
	}

	public int maxCompressionRatio() {
		return this.maxCompressionRatio;
	}

	@Override
	public String toString() {
		return "UploadLimitsProperties [maxCompressedBytes=" + this.maxCompressedBytes
				+ ", maxUncompressedBytes=" + this.maxUncompressedBytes + ", maxEntryBytes="
				+ this.maxEntryBytes + ", maxFileEntries=" + this.maxFileEntries
				+ ", maxCompressionRatio=" + this.maxCompressionRatio + "]";
	}

}
