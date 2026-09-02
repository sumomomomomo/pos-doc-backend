package horse.sumomo.pos_doc_backend.rendering.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import horse.sumomo.pos_doc_backend.ingestion.api.UploadLimitsProperties;

/**
 * Unit tests for {@link FirstPageRenderingProperties} validation rules.
 */
class FirstPageRenderingPropertiesTest {

	private static final long FIFTY_MIB = 52428800L;

	@Test
	void documentedDefaultsBindSuccessfully() {
		FirstPageRenderingProperties props = new FirstPageRenderingProperties(200, FIFTY_MIB, 5000, 5000,
				16000000L, 33554432L, 1);
		assertEquals(200, props.dpi());
		assertEquals(FIFTY_MIB, props.maxPdfBytes());
		assertEquals(5000, props.maxWidthPixels());
		assertEquals(5000, props.maxHeightPixels());
		assertEquals(16000000L, props.maxPixels());
		assertEquals(33554432L, props.maxPngBytes());
		assertEquals(1, props.maxConcurrentRenders());
	}

	@Test
	void dpiBelow72IsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new FirstPageRenderingProperties(71, FIFTY_MIB, 5000, 5000, 16000000L, 33554432L, 1));
	}

	@Test
	void dpiAbove300IsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new FirstPageRenderingProperties(301, FIFTY_MIB, 5000, 5000, 16000000L, 33554432L, 1));
	}

	@Test
	void dpiAtBoundariesIsAccepted() {
		new FirstPageRenderingProperties(72, FIFTY_MIB, 5000, 5000, 16000000L, 33554432L, 1);
		new FirstPageRenderingProperties(300, FIFTY_MIB, 5000, 5000, 16000000L, 33554432L, 1);
	}

	@Test
	void zeroMaxPdfBytesIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new FirstPageRenderingProperties(200, 0L, 5000, 5000, 16000000L, 33554432L, 1));
	}

	@Test
	void negativeMaxPdfBytesIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new FirstPageRenderingProperties(200, -1L, 5000, 5000, 16000000L, 33554432L, 1));
	}

	@Test
	void zeroMaxWidthPixelsIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new FirstPageRenderingProperties(200, FIFTY_MIB, 0, 5000, 16000000L, 33554432L, 1));
	}

	@Test
	void zeroMaxHeightPixelsIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new FirstPageRenderingProperties(200, FIFTY_MIB, 5000, 0, 16000000L, 33554432L, 1));
	}

	@Test
	void zeroMaxPixelsIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new FirstPageRenderingProperties(200, FIFTY_MIB, 5000, 5000, 0L, 33554432L, 1));
	}

	@Test
	void zeroMaxPngBytesIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new FirstPageRenderingProperties(200, FIFTY_MIB, 5000, 5000, 16000000L, 0L, 1));
	}

	@Test
	void maxConcurrentRendersOtherThanOneIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new FirstPageRenderingProperties(200, FIFTY_MIB, 5000, 5000, 16000000L, 33554432L, 2));
		assertThrows(IllegalArgumentException.class,
				() -> new FirstPageRenderingProperties(200, FIFTY_MIB, 5000, 5000, 16000000L, 33554432L, 0));
		assertThrows(IllegalArgumentException.class,
				() -> new FirstPageRenderingProperties(200, FIFTY_MIB, 5000, 5000, 16000000L, 33554432L, -1));
	}

	@Test
	void maxPdfBytesGreaterThanIngestionLimitIsRejected() {
		FirstPageRenderingProperties props = new FirstPageRenderingProperties(200, FIFTY_MIB + 1, 5000, 5000,
				16000000L, 33554432L, 1);
		UploadLimitsProperties upload = new UploadLimitsProperties(10485760L, 262144000L, FIFTY_MIB, 100, 100);
		assertThrows(IllegalArgumentException.class, () -> props.validateAgainstIngestionLimit(upload.maxEntryBytes()));
	}

	@Test
	void maxPdfBytesEqualToIngestionLimitIsAccepted() {
		FirstPageRenderingProperties props = new FirstPageRenderingProperties(200, FIFTY_MIB, 5000, 5000,
				16000000L, 33554432L, 1);
		UploadLimitsProperties upload = new UploadLimitsProperties(10485760L, 262144000L, FIFTY_MIB, 100, 100);
		props.validateAgainstIngestionLimit(upload.maxEntryBytes());
	}

	@Test
	void maxPdfBytesLessThanIngestionLimitIsAccepted() {
		FirstPageRenderingProperties props = new FirstPageRenderingProperties(200, FIFTY_MIB - 1, 5000, 5000,
				16000000L, 33554432L, 1);
		UploadLimitsProperties upload = new UploadLimitsProperties(10485760L, 262144000L, FIFTY_MIB, 100, 100);
		props.validateAgainstIngestionLimit(upload.maxEntryBytes());
	}

}
