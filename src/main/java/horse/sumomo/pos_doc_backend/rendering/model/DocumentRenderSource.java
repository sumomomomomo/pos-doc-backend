package horse.sumomo.pos_doc_backend.rendering.model;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Immutable snapshot of the metadata required to render one persisted PDF,
 * captured inside a short read-only transaction and usable after the
 * transaction closes.
 *
 * <p>Contains no JPA entities, filenames, policy metadata, names, or lazy
 * proxies. The SHA-256 value is normalized to lowercase.
 */
public record DocumentRenderSource(UUID posRecordId, UUID documentId, UUID storageObjectId,
		String objectKey, long expectedByteSize, String expectedSha256) {

	private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-fA-F]{64}");

	public DocumentRenderSource {
		if (posRecordId == null) {
			throw new IllegalArgumentException("posRecordId must not be null");
		}
		if (documentId == null) {
			throw new IllegalArgumentException("documentId must not be null");
		}
		if (storageObjectId == null) {
			throw new IllegalArgumentException("storageObjectId must not be null");
		}
		if (objectKey == null || objectKey.isBlank()) {
			throw new IllegalArgumentException("objectKey must not be blank");
		}
		if (expectedByteSize <= 0) {
			throw new IllegalArgumentException("expectedByteSize must be positive");
		}
		if (expectedSha256 == null || !SHA256_PATTERN.matcher(expectedSha256).matches()) {
			throw new IllegalArgumentException("expectedSha256 must contain exactly 64 hexadecimal characters");
		}
		expectedSha256 = expectedSha256.toLowerCase(Locale.ROOT);
	}

	@Override
	public String toString() {
		// Object key and hash are deliberately omitted: they are not safe to
		// log. Only generated UUIDs and the declared size are included.
		return "DocumentRenderSource[posRecordId=" + this.posRecordId + ", documentId=" + this.documentId
				+ ", storageObjectId=" + this.storageObjectId + ", expectedByteSize=" + this.expectedByteSize
				+ "]";
	}

}
