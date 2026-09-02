package horse.sumomo.pos_doc_backend.rendering.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Immutable {@link AutoCloseable} handle for one rendered first-page PNG.
 *
 * <p>The handle exclusively owns {@code pngPath}. {@link #close()} is
 * idempotent and deletes the PNG. If deletion fails, a sanitized
 * {@link RenderingException} with
 * {@link RenderingException.Code#TEMP_STORAGE_UNAVAILABLE} is thrown without
 * including the path.
 *
 * <p>No byte-array convenience method is exposed. The PDF temp path is not
 * exposed. {@link #toString()} omits {@code pngPath}.
 */
public final class RenderedFirstPage implements AutoCloseable {

	private final UUID documentId;
	private final Path pngPath;
	private final int widthPixels;
	private final int heightPixels;
	private final int dpi;
	private final long pngByteSize;
	private boolean closed;

	public RenderedFirstPage(UUID documentId, Path pngPath, int widthPixels, int heightPixels, int dpi,
			long pngByteSize) {
		if (documentId == null) {
			throw new IllegalArgumentException("documentId must not be null");
		}
		if (pngPath == null) {
			throw new IllegalArgumentException("pngPath must not be null");
		}
		if (widthPixels <= 0) {
			throw new IllegalArgumentException("widthPixels must be positive");
		}
		if (heightPixels <= 0) {
			throw new IllegalArgumentException("heightPixels must be positive");
		}
		if (dpi <= 0) {
			throw new IllegalArgumentException("dpi must be positive");
		}
		if (pngByteSize <= 0) {
			throw new IllegalArgumentException("pngByteSize must be positive");
		}
		this.documentId = documentId;
		this.pngPath = pngPath;
		this.widthPixels = widthPixels;
		this.heightPixels = heightPixels;
		this.dpi = dpi;
		this.pngByteSize = pngByteSize;
	}

	public UUID documentId() {
		return this.documentId;
	}

	public Path pngPath() {
		return this.pngPath;
	}

	public int widthPixels() {
		return this.widthPixels;
	}

	public int heightPixels() {
		return this.heightPixels;
	}

	public int dpi() {
		return this.dpi;
	}

	public long pngByteSize() {
		return this.pngByteSize;
	}

	@Override
	public void close() {
		if (this.closed) {
			return;
		}
		this.closed = true;
		try {
			Files.deleteIfExists(this.pngPath);
		}
		catch (IOException e) {
			// Sanitized: no path in the message.
			throw new horse.sumomo.pos_doc_backend.rendering.service.RenderingException(
					horse.sumomo.pos_doc_backend.rendering.service.RenderingException.Code.TEMP_STORAGE_UNAVAILABLE,
					e);
		}
	}

	@Override
	public String toString() {
		return "RenderedFirstPage[documentId=" + this.documentId + ", widthPixels=" + this.widthPixels
				+ ", heightPixels=" + this.heightPixels + ", dpi=" + this.dpi + ", pngByteSize="
				+ this.pngByteSize + "]";
	}

}
