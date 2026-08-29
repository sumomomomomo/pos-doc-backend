package horse.sumomo.pos_doc_backend.infrastructure.minio;

import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.minio.MinioClient;

/**
 * Exposes the shared {@link MinioClient} bean.
 *
 * <p>Building the client does not open any network connection, so normal
 * application startup never depends on a reachable MinIO server. Bucket
 * provisioning is intentionally <em>not</em> done here; it belongs to the
 * one-shot {@code minio-init} container in the Compose stack.
 */
@Configuration
@ConfigurationPropertiesScan
public class MinioConfiguration {

	@Bean
	public MinioClient minioClient(MinioProperties properties) {
		return MinioClient.builder()
			.endpoint(properties.endpoint())
			.credentials(properties.accessKey(), properties.secretKey())
			.build();
	}

}
