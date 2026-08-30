package horse.sumomo.pos_doc_backend.controller;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the <em>servlet-level</em> multipart size guard (Task 4-5, step 15
 * + blocking finding #3).
 *
 * <p>{@code spring.servlet.multipart.max-file-size} is 10 MiB. An upload
 * larger than that is rejected by the servlet container <em>before</em> the
 * request reaches {@code BoundedUploadSpooler}, raising
 * {@code MaxUploadSizeExceededException}. This test drives the real servlet
 * layer (real Spring context + MockMvc, no mocked service) with a genuinely
 * oversized part and proves the response is the required
 * {@code 413 ARCHIVE_TOO_LARGE} problem, not the generic {@code 500}.
 *
 * <p>The full application context is loaded; the scheduled outbox relay is
 * disabled so no broker is contacted, and a temporary SQLite database is used
 * so the context boots without a reachable MinIO/RabbitMQ (both clients are
 * constructed lazily and never contacted here).
 */
@SpringBootTest(properties = {
		"app.messaging.outbox.enabled=false"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UploadSizeRejectionIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@DynamicPropertySource
	static void sqliteUrl(DynamicPropertyRegistry registry) throws Exception {
		Path dbFile = Files.createTempFile("pos-doc-oversize-test", ".db");
		dbFile.toFile().deleteOnExit();
		Path.of(dbFile.toString() + "-wal").toFile().deleteOnExit();
		Path.of(dbFile.toString() + "-shm").toFile().deleteOnExit();
		registry.add("SQLITE_URL", () -> "jdbc:sqlite:" + dbFile);
	}

	@Test
	void oversizeMultipartIsRejectedWith413Not500() throws Exception {
		// 10 MiB limit; this is 10 MiB + 1 byte, so the servlet container
		// rejects it during multipart resolution.
		int oversizeBytes = 10 * 1024 * 1024 + 1;
		MockMultipartFile file = new MockMultipartFile("file", "EREF-OVERSIZE-001.zip",
				"application/zip", new byte[oversizeBytes]);

		mockMvc.perform(multipart("/pos-records").file(file))
				.andExpect(status().isPayloadTooLarge())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(413))
				.andExpect(jsonPath("$.code").value("ARCHIVE_TOO_LARGE"));
	}

}
