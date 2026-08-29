package horse.sumomo.pos_doc_backend.infrastructure.minio;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed, validated configuration for the MinIO object store.
 *
 * <p>Bound from {@code storage.minio.*}. The secret key is deliberately kept
 * out of {@link #toString()} so it cannot leak through logging or diagnostic
 * output.
 */
@ConfigurationProperties(prefix = "storage.minio")
public final class MinioProperties {

	private final String endpoint;
	private final String accessKey;
	private final String secretKey;
	private final String bucket;

	public MinioProperties(String endpoint, String accessKey, String secretKey, String bucket) {
		if (isBlank(endpoint)) {
			throw new IllegalArgumentException("storage.minio.endpoint must not be blank");
		}
		URI uri;
		try {
			uri = URI.create(endpoint.trim()).toURL().toURI();
		}
		catch (MalformedURLException | URISyntaxException e) {
			throw new IllegalArgumentException(
					"storage.minio.endpoint must be a valid HTTP or HTTPS URI: " + endpoint, e);
		}
		String scheme = uri.getScheme();
		if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
			throw new IllegalArgumentException(
					"storage.minio.endpoint must use the http or https scheme: " + endpoint);
		}
		if (isBlank(accessKey)) {
			throw new IllegalArgumentException("storage.minio.access-key must not be blank");
		}
		if (isBlank(secretKey)) {
			throw new IllegalArgumentException("storage.minio.secret-key must not be blank");
		}
		if (isBlank(bucket)) {
			throw new IllegalArgumentException("storage.minio.bucket must not be blank");
		}
		this.endpoint = endpoint.trim();
		this.accessKey = accessKey.trim();
		this.secretKey = secretKey.trim();
		this.bucket = bucket.trim();
	}

	public String endpoint() {
		return this.endpoint;
	}

	public String accessKey() {
		return this.accessKey;
	}

	public String secretKey() {
		return this.secretKey;
	}

	public String bucket() {
		return this.bucket;
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	@Override
	public String toString() {
		return "MinioProperties [endpoint=" + this.endpoint + ", accessKey=" + this.accessKey
				+ ", secretKey=******, bucket=" + this.bucket + "]";
	}

}
