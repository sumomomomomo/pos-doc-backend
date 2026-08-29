package horse.sumomo.pos_doc_backend.infrastructure.minio;

import java.io.InputStream;

import org.springframework.stereotype.Component;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.ObjectWriteResponse;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.MinioException;

/**
 * Thin object-storage adapter over the configured MinIO bucket.
 *
 * <p>All operations use the bucket from {@link MinioProperties}. Blank object
 * keys are rejected locally before any remote call. Only MinIO's
 * {@code NoSuchKey} / {@code NoSuchObject} responses are reported as
 * {@code false} from {@link #exists(String)}; every other failure (missing
 * bucket, connection failure, timeout, authentication failure) is wrapped in
 * {@link ObjectStorageException} so it cannot be misread as a missing object.
 */
@Component
public class MinioObjectStorage {

	private static final String NO_SUCH_KEY = "NoSuchKey";
	private static final String NO_SUCH_OBJECT = "NoSuchObject";

	private final MinioClient minioClient;
	private final MinioProperties properties;

	public MinioObjectStorage(MinioClient minioClient, MinioProperties properties) {
		this.minioClient = minioClient;
		this.properties = properties;
	}

	public void put(String objectKey, InputStream input, long size, String contentType) {
		validateObjectKey(objectKey);
		if (size < 0) {
			throw new IllegalArgumentException("size must not be negative: " + size);
		}
		if (contentType == null || contentType.isBlank()) {
			throw new IllegalArgumentException("contentType must not be blank");
		}
		try {
			ObjectWriteResponse response = this.minioClient.putObject(PutObjectArgs.builder()
				.bucket(this.properties.bucket())
				.object(objectKey)
				.stream(input, size, -1L)
				.contentType(contentType)
				.build());
			if (response == null) {
				throw new ObjectStorageException("putObject returned no result for object key: " + objectKey);
			}
		}
		catch (MinioException e) {
			throw new ObjectStorageException("Failed to put object: " + objectKey, e);
		}
		catch (RuntimeException e) {
			throw new ObjectStorageException("Failed to put object: " + objectKey, e);
		}
	}

	public InputStream get(String objectKey) {
		validateObjectKey(objectKey);
		try {
			return this.minioClient.getObject(GetObjectArgs.builder()
				.bucket(this.properties.bucket())
				.object(objectKey)
				.build());
		}
		catch (ErrorResponseException e) {
			if (isNoSuchKey(e)) {
				throw new ObjectStorageException("Object not found: " + objectKey, e);
			}
			throw new ObjectStorageException("Failed to get object: " + objectKey, e);
		}
		catch (MinioException e) {
			throw new ObjectStorageException("Failed to get object: " + objectKey, e);
		}
		catch (RuntimeException e) {
			throw new ObjectStorageException("Failed to get object: " + objectKey, e);
		}
	}

	public boolean exists(String objectKey) {
		validateObjectKey(objectKey);
		try {
			this.minioClient.statObject(StatObjectArgs.builder()
				.bucket(this.properties.bucket())
				.object(objectKey)
				.build());
			return true;
		}
		catch (ErrorResponseException e) {
			if (isNoSuchKey(e)) {
				return false;
			}
			// Missing bucket, auth failure, network error, etc.
			throw new ObjectStorageException("Failed to check existence of object: " + objectKey, e);
		}
		catch (MinioException e) {
			throw new ObjectStorageException("Failed to check existence of object: " + objectKey, e);
		}
		catch (RuntimeException e) {
			throw new ObjectStorageException("Failed to check existence of object: " + objectKey, e);
		}
	}

	public void delete(String objectKey) {
		validateObjectKey(objectKey);
		try {
			this.minioClient.removeObject(RemoveObjectArgs.builder()
				.bucket(this.properties.bucket())
				.object(objectKey)
				.build());
		}
		catch (MinioException e) {
			throw new ObjectStorageException("Failed to delete object: " + objectKey, e);
		}
		catch (RuntimeException e) {
			throw new ObjectStorageException("Failed to delete object: " + objectKey, e);
		}
	}

	private static void validateObjectKey(String objectKey) {
		if (objectKey == null || objectKey.isBlank()) {
			throw new IllegalArgumentException("objectKey must not be blank");
		}
	}

	private static boolean isNoSuchKey(ErrorResponseException e) {
		if (e.errorResponse() == null) {
			return false;
		}
		String code = e.errorResponse().code();
		return NO_SUCH_KEY.equalsIgnoreCase(code) || NO_SUCH_OBJECT.equalsIgnoreCase(code);
	}

}
