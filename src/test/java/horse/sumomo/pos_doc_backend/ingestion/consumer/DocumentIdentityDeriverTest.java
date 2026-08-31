package horse.sumomo.pos_doc_backend.ingestion.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class DocumentIdentityDeriverTest {

	@Test
	void documentIdIsDeterministicForSameInputs() {
		UUID posRecordId = UUID.randomUUID();
		UUID a = DocumentIdentityDeriver.deriveDocumentId(posRecordId, 0);
		UUID b = DocumentIdentityDeriver.deriveDocumentId(posRecordId, 0);
		assertEquals(a, b);
	}

	@Test
	void documentIdDiffersBySequence() {
		UUID posRecordId = UUID.randomUUID();
		UUID first = DocumentIdentityDeriver.deriveDocumentId(posRecordId, 0);
		UUID second = DocumentIdentityDeriver.deriveDocumentId(posRecordId, 1);
		assertNotEquals(first, second);
	}

	@Test
	void documentIdDoesNotEncodeFilename() {
		UUID posRecordId = UUID.randomUUID();
		UUID id = DocumentIdentityDeriver.deriveDocumentId(posRecordId, 0);
		String key = DocumentIdentityDeriver.buildDocumentObjectKey(posRecordId, id);
		assertTrue(key.startsWith("documents/" + posRecordId + "/"));
		assertTrue(key.endsWith(".pdf"));
		// No segment of the key may echo a filename, policy number, eRef,
		// or hash. The full key must be UUID-only.
		assertTrue(key.matches("documents/[0-9a-f-]{36}/[0-9a-f-]{36}\\.pdf"),
				"document object key must be UUID-only");
	}

	@Test
	void storageObjectIdIsDeterministicForSameDocument() {
		UUID documentId = UUID.randomUUID();
		UUID a = DocumentIdentityDeriver.deriveStorageObjectId(documentId);
		UUID b = DocumentIdentityDeriver.deriveStorageObjectId(documentId);
		assertEquals(a, b);
	}

	@Test
	void storageObjectIdIsDifferentNamespaceFromDocumentId() {
		UUID documentId = UUID.randomUUID();
		UUID s = DocumentIdentityDeriver.deriveStorageObjectId(documentId);
		assertNotEquals(documentId, s);
	}

	@Test
	void negativeSequenceIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> DocumentIdentityDeriver.deriveDocumentId(UUID.randomUUID(), -1));
	}

	@Test
	void nullPosRecordIdIsRejected() {
		assertThrows(NullPointerException.class, () -> DocumentIdentityDeriver.deriveDocumentId(null, 0));
	}

	@Test
	void documentAndStorageIdsArePinned() {
		UUID posRecordId = UUID.fromString("11111111-2222-3333-4444-555555555555");
		UUID documentId = DocumentIdentityDeriver.deriveDocumentId(posRecordId, 0);
		UUID storageId = DocumentIdentityDeriver.deriveStorageObjectId(documentId);
		// Determinism: pin the exact UUIDs so a future refactor cannot
		// accidentally change the namespace or hash function.
		assertEquals(UUID.fromString("84eb6a20-60da-34d4-967d-e1c115c46d1a"), documentId);
		assertEquals(UUID.fromString("32381ec7-0238-3ff2-a855-7991d2eea051"), storageId);
	}

}