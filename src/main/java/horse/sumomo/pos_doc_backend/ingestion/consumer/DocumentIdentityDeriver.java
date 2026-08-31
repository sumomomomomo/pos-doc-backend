package horse.sumomo.pos_doc_backend.ingestion.consumer;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import horse.sumomo.pos_doc_backend.ingestion.messaging.IngestionRequestedMessage;

/**
 * Derives deterministic identifiers and object keys for extracted PDFs.
 *
 * <p>Determinism is required so a redelivered message produces the same
 * document ID and storage object ID and key. Inputs are limited to
 * generated UUIDs and a sequence number; nothing PII-sensitive (filenames,
 * eRef numbers, policy numbers, hashes) ever feeds into the derivation, and
 * nothing PII-sensitive (filenames, entry names, eRef values, hashes) ever
 * appears in the resulting key.
 *
 * <p>The two namespaces are fixed ASCII labels that pin the outputs so tests
 * can assert exact strings.
 */
public final class DocumentIdentityDeriver {

	/** Fixed namespace for {@link #deriveDocumentId}. */
	public static final String DOCUMENT_NAMESPACE = "pos-doc-doc-v1";

	/** Fixed namespace for {@link #deriveStorageObjectId}. */
	public static final String STORAGE_OBJECT_NAMESPACE = "pos-doc-storage-v1";

	private DocumentIdentityDeriver() {
	}

	/**
	 * Derives a deterministic document UUID from a POS record ID and a
	 * zero-based entry sequence number.
	 *
	 * @param posRecordId the POS record UUID (never null)
	 * @param sequence    zero-based entry sequence number (>= 0)
	 * @return a deterministic UUID
	 */
	public static UUID deriveDocumentId(UUID posRecordId, int sequence) {
		Objects.requireNonNull(posRecordId, "posRecordId must not be null");
		if (sequence < 0) {
			throw new IllegalArgumentException("sequence must be >= 0");
		}
		return UUID.nameUUIDFromBytes(
				(DOCUMENT_NAMESPACE + ":" + posRecordId + ":" + sequence).getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	/**
	 * Derives a deterministic storage-object UUID from a document UUID.
	 */
	public static UUID deriveStorageObjectId(UUID documentId) {
		Objects.requireNonNull(documentId, "documentId must not be null");
		return UUID.nameUUIDFromBytes(
				(STORAGE_OBJECT_NAMESPACE + ":" + documentId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	/**
	 * Builds the deterministic PII-free MinIO object key for one extracted
	 * PDF.
	 */
	public static String buildDocumentObjectKey(UUID posRecordId, UUID documentId) {
		Objects.requireNonNull(posRecordId, "posRecordId must not be null");
		Objects.requireNonNull(documentId, "documentId must not be null");
		return "documents/" + posRecordId + "/" + documentId + ".pdf";
	}

	/**
	 * Returns a hex-encoded SHA-256 representation normalized to lowercase.
	 * Used for log/test assertions; identical input always produces the
	 * same string.
	 */
	public static String normalizeHex(byte[] bytes) {
		Objects.requireNonNull(bytes, "bytes must not be null");
		StringBuilder sb = new StringBuilder(bytes.length * 2);
		for (byte b : bytes) {
			sb.append(Character.forDigit((b >> 4) & 0xF, 16));
			sb.append(Character.forDigit(b & 0xF, 16));
		}
		return sb.toString().toLowerCase(Locale.ROOT);
	}

	/**
	 * Pin tests can use to assert that the same input message always
	 * produces the same output identifiers.
	 */
	public static UUID messageSeed(IngestionRequestedMessage message) {
		Objects.requireNonNull(message, "message must not be null");
		return message.jobId();
	}

}