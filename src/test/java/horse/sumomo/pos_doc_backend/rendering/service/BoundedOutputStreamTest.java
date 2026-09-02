package horse.sumomo.pos_doc_backend.rendering.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BoundedOutputStream}, including the single-byte
 * method, array method, exact-boundary success, one-byte-over failure, and
 * arithmetic near {@link Long#MAX_VALUE} without allocating a large array.
 */
class BoundedOutputStreamTest {

	@Test
	void singleByteWriteIsCounted() throws Exception {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		BoundedOutputStream out = new BoundedOutputStream(baos, 10L);
		out.write('A');
		assertEquals(1L, out.getCount());
		out.close();
		assertEquals("A", baos.toString());
	}

	@Test
	void arrayWriteIsCounted() throws Exception {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		BoundedOutputStream out = new BoundedOutputStream(baos, 10L);
		byte[] data = "hello".getBytes();
		out.write(data, 0, data.length);
		assertEquals(5L, out.getCount());
		out.close();
		assertEquals("hello", baos.toString());
	}

	@Test
	void exactBoundarySucceeds() throws Exception {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		BoundedOutputStream out = new BoundedOutputStream(baos, 5L);
		byte[] data = "12345".getBytes();
		out.write(data, 0, data.length);
		assertEquals(5L, out.getCount());
		out.close();
		assertEquals("12345", baos.toString());
	}

	@Test
	void oneByteOverFails() throws Exception {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		BoundedOutputStream out = new BoundedOutputStream(baos, 5L);
		byte[] data = "123456".getBytes();
		RenderingException e = assertThrows(RenderingException.class,
				() -> out.write(data, 0, data.length));
		assertEquals(RenderingException.Code.RENDER_LIMIT_EXCEEDED, e.getCode());
	}

	@Test
	void singleByteOverFails() throws Exception {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		BoundedOutputStream out = new BoundedOutputStream(baos, 5L);
		out.write("12345".getBytes(), 0, 5);
		RenderingException e = assertThrows(RenderingException.class, () -> out.write('6'));
		assertEquals(RenderingException.Code.RENDER_LIMIT_EXCEEDED, e.getCode());
	}

	@Test
	void mixedWritesAreCounted() throws Exception {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		BoundedOutputStream out = new BoundedOutputStream(baos, 10L);
		out.write('A');
		out.write("BC".getBytes(), 0, 2);
		out.write('D');
		assertEquals(4L, out.getCount());
		out.close();
		assertEquals("ABCD", baos.toString());
	}

	@Test
	void zeroLengthArrayWriteSucceeds() throws Exception {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		BoundedOutputStream out = new BoundedOutputStream(baos, 1L);
		out.write(new byte[0], 0, 0);
		assertEquals(0L, out.getCount());
		out.close();
	}

	@Test
	void arithmeticNearLongMaxValueDoesNotAllocateLargeArray() throws Exception {
		// Use the package-private constructor to set an initial count near
		// Long.MAX_VALUE without writing an enormous file.
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		long maxBytes = Long.MAX_VALUE;
		long initialCount = Long.MAX_VALUE - 5;
		BoundedOutputStream out = new BoundedOutputStream(baos, maxBytes, initialCount);

		// Writing 5 bytes should succeed (exactly at the boundary).
		out.write("12345".getBytes(), 0, 5);
		assertEquals(Long.MAX_VALUE, out.getCount());

		// Writing one more byte should fail without overflow.
		RenderingException e = assertThrows(RenderingException.class, () -> out.write('6'));
		assertEquals(RenderingException.Code.RENDER_LIMIT_EXCEEDED, e.getCode());
	}

	@Test
	void arithmeticNearLongMaxValueWithArrayWrite() throws Exception {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		long maxBytes = Long.MAX_VALUE;
		long initialCount = Long.MAX_VALUE - 3;
		BoundedOutputStream out = new BoundedOutputStream(baos, maxBytes, initialCount);

		// Writing 3 bytes should succeed.
		out.write("123".getBytes(), 0, 3);
		assertEquals(Long.MAX_VALUE, out.getCount());

		// Writing 1 more byte should fail.
		RenderingException e = assertThrows(RenderingException.class,
				() -> out.write("4".getBytes(), 0, 1));
		assertEquals(RenderingException.Code.RENDER_LIMIT_EXCEEDED, e.getCode());
	}

	@Test
	void toStringContainsNoPii() {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		BoundedOutputStream out = new BoundedOutputStream(baos, 100L);
		String str = out.toString();
		assertTrue(str.contains("maxBytes=100"));
		assertTrue(str.contains("count=0"));
	}

}
