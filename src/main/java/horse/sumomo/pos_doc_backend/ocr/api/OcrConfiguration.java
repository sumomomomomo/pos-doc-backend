package horse.sumomo.pos_doc_backend.ocr.api;

import java.net.Proxy;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import horse.sumomo.pos_doc_backend.ocr.client.LlamaCppOcrClient;
import horse.sumomo.pos_doc_backend.ocr.application.FirstPageOcrService;
import horse.sumomo.pos_doc_backend.rendering.api.FirstPageRenderingProperties;
import horse.sumomo.pos_doc_backend.rendering.application.FirstPageRenderPreparationService;
import okhttp3.OkHttpClient;

/**
 * Registers the OCR {@link LlamaCppOcrProperties} bean, performs the
 * cross-property validation that {@code max-image-bytes} must not exceed the
 * rendering PNG limit, and creates the shared OkHttp client and OCR client
 * beans.
 *
 * <p>Building the OkHttp client does not open any network connection, so
 * normal application startup never depends on a reachable llama.cpp server.
 */
@Configuration
@EnableConfigurationProperties(LlamaCppOcrProperties.class)
public class OcrConfiguration {

	private static final Logger log = LoggerFactory.getLogger(OcrConfiguration.class);

	@Bean
	public Object ocrPropertiesValidator(LlamaCppOcrProperties ocr, FirstPageRenderingProperties rendering) {
		ocr.validateAgainstRenderingLimit(rendering.maxPngBytes());
		log.debug("OCR properties validated; {}", ocr);
		return new Object();
	}

	@Bean
	public OkHttpClient ocrOkHttpClient(LlamaCppOcrProperties properties) {
		return new OkHttpClient.Builder()
				.connectTimeout(properties.connectTimeout())
				.readTimeout(properties.readTimeout())
				.callTimeout(properties.callTimeout())
				.followRedirects(false)
				.followSslRedirects(false)
				.proxy(Proxy.NO_PROXY)
				.build();
	}

	@Bean
	public LlamaCppOcrClient llamaCppOcrClient(OkHttpClient ocrOkHttpClient, LlamaCppOcrProperties properties) {
		return new LlamaCppOcrClient(ocrOkHttpClient, properties);
	}

	@Bean
	public FirstPageOcrService firstPageOcrService(FirstPageRenderPreparationService renderPreparationService,
			LlamaCppOcrClient ocrClient) {
		return new FirstPageOcrService(renderPreparationService, ocrClient);
	}

}
