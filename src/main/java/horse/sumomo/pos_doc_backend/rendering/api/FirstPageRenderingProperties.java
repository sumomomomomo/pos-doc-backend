package horse.sumomo.pos_doc_backend.rendering.api;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed, validated limits for bounded first-page PDF rendering.
 *
 * <p>Bound from {@code app.rendering.first-page.*}. All numeric values must be
 * positive; {@code dpi} must be within {@code [72, 300]};
 * {@code max-concurrent-renders} is pinned to exactly {@code 1} for Task 7 and
 * any other value is rejected at startup; {@code max-pdf-bytes} must not
 * exceed the existing per-PDF ingestion limit
 * ({@code app.upload.max-entry-bytes}).
 *
 * <p>{@link #toString()} exposes only numeric limits that are safe to log.
 */
@ConfigurationProperties(prefix = "app.rendering.first-page")
public final class FirstPageRenderingProperties {

	static final int MIN_DPI = 72;
	static final int MAX_DPI = 300;

	private final int dpi;
	private final long maxPdfBytes;
	private final int maxWidthPixels;
	private final int maxHeightPixels;
	private final long maxPixels;
	private final long maxPngBytes;
	private final int maxConcurrentRenders;

	public FirstPageRenderingProperties(int dpi, long maxPdfBytes, int maxWidthPixels, int maxHeightPixels,
			long maxPixels, long maxPngBytes, int maxConcurrentRenders) {
		if (dpi < MIN_DPI || dpi > MAX_DPI) {
			throw new IllegalArgumentException(
					"app.rendering.first-page.dpi must be within [" + MIN_DPI + ", " + MAX_DPI + "]");
		}
		if (maxPdfBytes <= 0) {
			throw new IllegalArgumentException("app.rendering.first-page.max-pdf-bytes must be positive");
		}
		if (maxWidthPixels <= 0) {
			throw new IllegalArgumentException("app.rendering.first-page.max-width-pixels must be positive");
		}
		if (maxHeightPixels <= 0) {
			throw new IllegalArgumentException("app.rendering.first-page.max-height-pixels must be positive");
		}
		if (maxPixels <= 0) {
			throw new IllegalArgumentException("app.rendering.first-page.max-pixels must be positive");
		}
		if (maxPngBytes <= 0) {
			throw new IllegalArgumentException("app.rendering.first-page.max-png-bytes must be positive");
		}
		if (maxConcurrentRenders != 1) {
			throw new IllegalArgumentException(
					"app.rendering.first-page.max-concurrent-renders must be exactly 1");
		}
		this.dpi = dpi;
		this.maxPdfBytes = maxPdfBytes;
		this.maxWidthPixels = maxWidthPixels;
		this.maxHeightPixels = maxHeightPixels;
		this.maxPixels = maxPixels;
		this.maxPngBytes = maxPngBytes;
		this.maxConcurrentRenders = maxConcurrentRenders;
	}

	/**
	 * Validates the cross-property rule that the rendering PDF limit must not
	 * exceed the ingestion per-entry limit. Invoked at startup by the
	 * rendering configuration.
	 */
	public void validateAgainstIngestionLimit(long ingestionMaxEntryBytes) {
		if (ingestionMaxEntryBytes <= 0) {
			throw new IllegalArgumentException("ingestion max-entry-bytes must be positive");
		}
		if (this.maxPdfBytes > ingestionMaxEntryBytes) {
			throw new IllegalArgumentException(
					"app.rendering.first-page.max-pdf-bytes must be <= app.upload.max-entry-bytes");
		}
	}

	public int dpi() {
		return this.dpi;
	}

	public long maxPdfBytes() {
		return this.maxPdfBytes;
	}

	public int maxWidthPixels() {
		return this.maxWidthPixels;
	}

	public int maxHeightPixels() {
		return this.maxHeightPixels;
	}

	public long maxPixels() {
		return this.maxPixels;
	}

	public long maxPngBytes() {
		return this.maxPngBytes;
	}

	public int maxConcurrentRenders() {
		return this.maxConcurrentRenders;
	}

	@Override
	public String toString() {
		return "FirstPageRenderingProperties [dpi=" + this.dpi + ", maxPdfBytes=" + this.maxPdfBytes
				+ ", maxWidthPixels=" + this.maxWidthPixels + ", maxHeightPixels=" + this.maxHeightPixels
				+ ", maxPixels=" + this.maxPixels + ", maxPngBytes=" + this.maxPngBytes
				+ ", maxConcurrentRenders=" + this.maxConcurrentRenders + "]";
	}

}
