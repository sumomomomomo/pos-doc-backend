package horse.sumomo.pos_doc_backend.content;

/**
 * Immutable, PII-bounded descriptor for a protected binary object, produced inside
 * a short read-only transaction and consumed after the transaction has closed.
 *
 * <p>It carries only the values needed to stream the object: the storage object id,
 * the internal object key, the trusted content type, the trusted original filename,
 * and the expected byte size. It never references a live JPA entity, so the
 * transaction may safely end before any object-store contact.
 */
public record ContentDescriptor(String storageObjectId, String objectKey, String contentType,
		String originalFilename, long expectedByteSize) {

}
