package horse.sumomo.pos_doc_backend.rendering.service;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * A {@link FilterOutputStream} that counts every written byte and enforces a
 * hard byte limit using overflow-safe arithmetic.
 *
 * <p>Both {@link #write(int)} and {@link #write(byte[], int, int)} are
 * counted. The limit is checked <em>before</em> delegating the write, so the
 * count can never exceed the limit. A dedicated
 * {@link RenderingException} with {@link RenderingException.Code#RENDER_LIMIT_EXCEEDED}
 * is thrown when the limit would be exceeded.
 *
 * <p>Exception messages and {@link #toString()} contain no written bytes,
 * paths, or other PII.
 */
public class BoundedOutputStream extends FilterOutputStream {

	private final long maxBytes;
	private long count;

	/**
	 * Creates a bounded stream with an initial count of zero.
	 *
	 * @param out      the underlying output stream
	 * @param maxBytes the maximum number of bytes that may be written
	 */
	public BoundedOutputStream(OutputStream out, long maxBytes) {
		super(out);
		if (maxBytes <= 0) {
			throw new IllegalArgumentException("maxBytes must be positive");
		}
		this.maxBytes = maxBytes;
		this.count = 0L;
	}

	/**
	 * Package-private constructor that accepts an initial count solely so
	 * overflow behavior can be tested without writing an enormous file.
	 * Production code must use the public constructor whose initial count is
	 * zero.
	 */
	BoundedOutputStream(OutputStream out, long maxBytes, long initialCount) {
		super(out);
		if (maxBytes <= 0) {
			throw new IllegalArgumentException("maxBytes must be positive");
		}
		if (initialCount < 0) {
			throw new IllegalArgumentException("initialCount must not be negative");
		}
		this.maxBytes = maxBytes;
		this.count = initialCount;
	}

	@Override
	public void write(int b) throws IOException {
		checkLimit(1L);
		super.write(b);
		this.count++;
	}

	@Override
	public void write(byte[] buf, int off, int len) throws IOException {
		if (len < 0) {
			throw new IndexOutOfBoundsException("len must not be negative");
		}
		checkLimit(len);
		// Write directly to the underlying stream to avoid FilterOutputStream's
		// default behavior of calling write(int) for each byte (which would
		// double-count).
		out.write(buf, off, len);
		this.count += len;
	}

	private void checkLimit(long additional) {
		// Overflow-safe: if count + additional overflows long or exceeds
		// maxBytes, reject.
		if (additional > this.maxBytes - this.count) {
			throw new RenderingException(RenderingException.Code.RENDER_LIMIT_EXCEEDED);
		}
	}

	/**
	 * Returns the number of bytes written so far.
	 */
	public long getCount() {
		return this.count;
	}

	@Override
	public String toString() {
		return "BoundedOutputStream[maxBytes=" + this.maxBytes + ", count=" + this.count + "]";
	}

}
