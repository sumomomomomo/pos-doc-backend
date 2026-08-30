package horse.sumomo.pos_doc_backend.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.yourcompany.pos.api.model.Problem;

import horse.sumomo.pos_doc_backend.ingestion.application.IntakeException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Direct unit test of the {@link ApiExceptionHandler} advice for
 * {@code MaxUploadSizeExceededException} (Task 4-5, step 15 + corrective
 * finding #2 of the latest review).
 *
 * <p>Invokes the advice with a real
 * {@link MaxUploadSizeExceededException} and asserts the response is
 * {@code 413 ARCHIVE_TOO_LARGE} with a sanitized detail and
 * {@code application/problem+json} content type. No MockMvc and no servlet
 * container are involved; this proves the advice-to-problem mapping in
 * isolation.
 */
@SpringBootTest(classes = {ApiExceptionHandler.class})
class MaxUploadSizeAdviceTest {

	@Autowired
	private ApiExceptionHandler advice;

	@Test
	void maxUploadSizeExceptionMapsTo413ArchiveTooLarge() {
		MaxUploadSizeExceededException ex = new MaxUploadSizeExceededException(10L * 1024L * 1024L);

		ResponseEntity<Problem> response = this.advice.handleMaxUploadSize(ex);

		assertEquals(413, response.getStatusCode().value());
		assertEquals(MediaType.parseMediaType("application/problem+json"),
				response.getHeaders().getContentType());
		Problem body = response.getBody();
		assertNotNull(body);
		assertEquals(413, body.getStatus());
		assertEquals("ARCHIVE_TOO_LARGE", body.getCode());
		assertEquals(IntakeException.Code.ARCHIVE_TOO_LARGE.detail(), body.getDetail());
		// Sanitized: no exception class or message text leaks into the body.
		String rendered = body.toString();
		assertFalse(rendered.contains("MaxUploadSize"),
				"advice body must not echo the exception class name: " + rendered);
	}
}
