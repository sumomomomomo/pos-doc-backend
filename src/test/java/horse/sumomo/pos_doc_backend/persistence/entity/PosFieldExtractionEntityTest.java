package horse.sumomo.pos_doc_backend.persistence.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import horse.sumomo.pos_doc_backend.persistence.model.ExtractionOutcome;

/**
 * Unit tests for {@link PosFieldExtractionEntity} constructor invariants and
 * PII-safe identity/logging.
 */
class PosFieldExtractionEntityTest {

	private static final UUID DOC = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final Instant NOW = Instant.parse("2026-01-02T03:04:05Z");

	private static PosFieldExtractionId id() {
		return new PosFieldExtractionId(DOC, "POLICYHOLDER_NAME", 2);
	}

	@Test
	void resolvedRequiresNonblankValueAndNullError() {
		PosFieldExtractionEntity e = new PosFieldExtractionEntity(id(), ExtractionOutcome.RESOLVED,
				"Charlie Henry", "/models/m", "stop", 1, null, NOW);
		assertEquals("Charlie Henry", e.getValueText());
		assertThrows(IllegalArgumentException.class,
				() -> new PosFieldExtractionEntity(id(), ExtractionOutcome.RESOLVED, "  ", "/models/m", "stop", 1,
						null, NOW));
		assertThrows(IllegalArgumentException.class,
				() -> new PosFieldExtractionEntity(id(), ExtractionOutcome.RESOLVED, null, "/models/m", "stop", 1,
						null, NOW));
		assertThrows(IllegalArgumentException.class,
				() -> new PosFieldExtractionEntity(id(), ExtractionOutcome.RESOLVED, "Charlie Henry", "/models/m",
						"stop", 1, "SOME_ERROR", NOW));
	}

	@Test
	void unknownRequiresNullValueAndNullError() {
		PosFieldExtractionEntity e = new PosFieldExtractionEntity(id(), ExtractionOutcome.UNKNOWN, null,
				"/models/m", "stop", 1, null, NOW);
		assertEquals(ExtractionOutcome.UNKNOWN, e.getOutcome());
		assertThrows(IllegalArgumentException.class,
				() -> new PosFieldExtractionEntity(id(), ExtractionOutcome.UNKNOWN, "a value", "/models/m", "stop", 1,
						null, NOW));
		assertThrows(IllegalArgumentException.class,
				() -> new PosFieldExtractionEntity(id(), ExtractionOutcome.UNKNOWN, null, "/models/m", "stop", 1,
						"SOME_ERROR", NOW));
	}

	@Test
	void failedRequiresNullValueAndNonblankError() {
		PosFieldExtractionEntity e = new PosFieldExtractionEntity(id(), ExtractionOutcome.FAILED, null,
				"/models/m", null, 3, "OCR_TIMEOUT", NOW);
		assertEquals("OCR_TIMEOUT", e.getErrorCode());
		assertThrows(IllegalArgumentException.class,
				() -> new PosFieldExtractionEntity(id(), ExtractionOutcome.FAILED, "a value", "/models/m", null, 3,
						"OCR_TIMEOUT", NOW));
		assertThrows(IllegalArgumentException.class,
				() -> new PosFieldExtractionEntity(id(), ExtractionOutcome.FAILED, null, "/models/m", null, 3, null,
						NOW));
		assertThrows(IllegalArgumentException.class,
				() -> new PosFieldExtractionEntity(id(), ExtractionOutcome.FAILED, null, "/models/m", null, 3,
						"  ", NOW));
	}

	@Test
	void modelMustBeNonblank() {
		assertThrows(IllegalArgumentException.class,
				() -> new PosFieldExtractionEntity(id(), ExtractionOutcome.UNKNOWN, null, "  ", "stop", 1, null, NOW));
	}

	@Test
	void attemptCountMustBeBetweenOneAndThree() {
		assertThrows(IllegalArgumentException.class,
				() -> new PosFieldExtractionEntity(id(), ExtractionOutcome.UNKNOWN, null, "/models/m", "stop", 0, null,
						NOW));
		assertThrows(IllegalArgumentException.class,
				() -> new PosFieldExtractionEntity(id(), ExtractionOutcome.UNKNOWN, null, "/models/m", "stop", 4, null,
						NOW));
	}

	@Test
	void completedAtMustBeNonNull() {
		assertThrows(IllegalArgumentException.class,
				() -> new PosFieldExtractionEntity(id(), ExtractionOutcome.UNKNOWN, null, "/models/m", "stop", 1, null,
						null));
	}

	@Test
	void idMustBeNonNull() {
		assertThrows(IllegalArgumentException.class,
				() -> new PosFieldExtractionEntity(null, ExtractionOutcome.UNKNOWN, null, "/models/m", "stop", 1, null,
						NOW));
	}

	@Test
	void toStringAndIdentityExcludeValueAndError() {
		PosFieldExtractionEntity e = new PosFieldExtractionEntity(id(), ExtractionOutcome.RESOLVED,
				"SECRET_NAME_VALUE", "/models/m", "stop", 1, null, NOW);
		String str = e.toString();
		assertFalse(str.contains("SECRET_NAME_VALUE"), "toString must not include value text");

		PosFieldExtractionEntity failed = new PosFieldExtractionEntity(id(), ExtractionOutcome.FAILED, null,
				"/models/m", null, 3, "SECRET_ERROR_DETAIL", NOW);
		assertFalse(failed.toString().contains("SECRET_ERROR_DETAIL"),
				"toString must not include the error code detail");

		// Identity is by key only.
		PosFieldExtractionEntity sameKey = new PosFieldExtractionEntity(id(), ExtractionOutcome.FAILED, null,
				"/models/m", null, 3, "OTHER", NOW);
		assertEquals(e, sameKey);
		assertEquals(e.hashCode(), sameKey.hashCode());

		PosFieldExtractionEntity otherKey = new PosFieldExtractionEntity(
				new PosFieldExtractionId(DOC, "CONSULTANT_NAME", 2), ExtractionOutcome.RESOLVED, "x", "/models/m",
				"stop", 1, null, NOW);
		assertFalse(e.equals(otherKey));
		assertTrue(e.toString().contains("POLICYHOLDER_NAME"));
	}

}
