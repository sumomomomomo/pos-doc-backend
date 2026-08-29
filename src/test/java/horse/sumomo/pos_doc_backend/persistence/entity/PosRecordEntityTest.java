package horse.sumomo.pos_doc_backend.persistence.entity;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import horse.sumomo.pos_doc_backend.persistence.model.PosRecordStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for the paired display/normalized mutators on
 * {@link PosRecordEntity}: a value rejected by the normalizer must leave
 * both the display and the normalized field unchanged, and a valid value
 * must update both atomously in the same assignment.
 */
class PosRecordEntityTest {

	private static final String SHA256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

	private static PosRecordEntity newRecord() {
		StorageObjectEntity archive = new StorageObjectEntity(UUID.randomUUID(), "key-1", "file.zip",
				"application/zip", 100L, SHA256, Instant.ofEpochMilli(1_700_000_000_000L));
		Instant now = Instant.ofEpochMilli(1_700_000_000_000L);
		return new PosRecordEntity(UUID.randomUUID(), archive, PosRecordStatus.UPLOADED, "tester", now, now);
	}

	@Test
	void validErefUpdateWritesBothFieldsTogether() {
		PosRecordEntity record = newRecord();
		record.setErefNumber(" EREF-001 ");
		assertEquals(" EREF-001 ", record.getErefNumber());
		assertEquals("EREF001", record.getErefNumberNormalized());
	}

	@Test
	void rejectedErefUpdateLeavesBothFieldsUnchanged() {
		PosRecordEntity record = newRecord();
		record.setErefNumber(" EREF-001 ");
		assertThrows(IllegalArgumentException.class, () -> record.setErefNumber("---///"));
		assertEquals(" EREF-001 ", record.getErefNumber());
		assertEquals("EREF001", record.getErefNumberNormalized());
	}

	@Test
	void rejectedErefUpdateOnFreshRecordLeavesBothFieldsUnchanged() {
		PosRecordEntity record = newRecord();
		assertThrows(IllegalArgumentException.class, () -> record.setErefNumber("   "));
		assertNull(record.getErefNumber());
		assertNull(record.getErefNumberNormalized());
	}

	@Test
	void validPolicyNumberUpdateWritesBothFieldsTogether() {
		PosRecordEntity record = newRecord();
		record.setPolicyNumber("POLICY-001");
		assertEquals("POLICY-001", record.getPolicyNumber());
		assertEquals("POLICY001", record.getPolicyNumberNormalized());
	}

	@Test
	void rejectedPolicyNumberUpdateLeavesBothFieldsUnchanged() {
		PosRecordEntity record = newRecord();
		record.setPolicyNumber("POLICY-001");
		assertThrows(IllegalArgumentException.class, () -> record.setPolicyNumber("///"));
		assertEquals("POLICY-001", record.getPolicyNumber());
		assertEquals("POLICY001", record.getPolicyNumberNormalized());
	}

	@Test
	void rejectedPolicyholderNameUpdateLeavesBothFieldsUnchanged() {
		PosRecordEntity record = newRecord();
		record.setPolicyholderName("  Jane   TAN ");
		assertEquals("  Jane   TAN ", record.getPolicyholderName());
		assertEquals("jane tan", record.getPolicyholderNameNormalized());
		assertThrows(IllegalArgumentException.class, () -> record.setPolicyholderName(" \t\n "));
		assertEquals("  Jane   TAN ", record.getPolicyholderName());
		assertEquals("jane tan", record.getPolicyholderNameNormalized());
	}

	@Test
	void rejectedPolicyholderNameUpdateOnFreshRecordLeavesBothFieldsUnchanged() {
		PosRecordEntity record = newRecord();
		assertThrows(IllegalArgumentException.class, () -> record.setPolicyholderName("   "));
		assertNull(record.getPolicyholderName());
		assertNull(record.getPolicyholderNameNormalized());
	}

	@Test
	void nullErefClearsBothFields() {
		PosRecordEntity record = newRecord();
		record.setErefNumber(" EREF-001 ");
		record.setErefNumber(null);
		assertNull(record.getErefNumber());
		assertNull(record.getErefNumberNormalized());
	}

	@Test
	void nullPolicyholderNameClearsBothFields() {
		PosRecordEntity record = newRecord();
		record.setPolicyholderName("Jane Tan");
		record.setPolicyholderName(null);
		assertNull(record.getPolicyholderName());
		assertNull(record.getPolicyholderNameNormalized());
	}

	@Test
	void markDeletedIsIdempotentAndStampsUpdatedAtOnlyOnce() {
		PosRecordEntity record = newRecord();
		Instant first = Instant.ofEpochMilli(1_700_000_000_100L);
		Instant second = Instant.ofEpochMilli(1_700_000_000_200L);
		record.markDeleted(first);
		record.markDeleted(second);
		assertSame(first, record.getDeletedAt());
		assertSame(first, record.getUpdatedAt());
	}

}
