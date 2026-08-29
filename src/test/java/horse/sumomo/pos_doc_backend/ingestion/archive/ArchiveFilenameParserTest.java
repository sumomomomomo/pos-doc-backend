package horse.sumomo.pos_doc_backend.ingestion.archive;

import java.util.Locale;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ArchiveFilenameParser}.
 */
class ArchiveFilenameParserTest {

	@Test
	void normalZipSuffixedNameIsAccepted() {
		ArchiveFilenameParser.ParsedFilename parsed = ArchiveFilenameParser.parse("EREF-2026-001.zip");
		assertEquals("EREF-2026-001.zip", parsed.safeFilename());
		assertEquals("EREF-2026-001", parsed.displayEref());
		assertEquals("EREF2026001", parsed.normalizedEref());
	}

	@Test
	void uppercaseZipSuffixIsAccepted() {
		ArchiveFilenameParser.ParsedFilename parsed = ArchiveFilenameParser.parse("EREF-2026-001.ZIP");
		assertEquals("EREF-2026-001", parsed.displayEref());
		assertEquals("EREF2026001", parsed.normalizedEref());
	}

	@Test
	void windowsFakePathRetainsOnlyFinalSegment() {
		ArchiveFilenameParser.ParsedFilename parsed = ArchiveFilenameParser.parse("C:\\fakepath\\EREF-2026-001.zip");
		assertEquals("EREF-2026-001.zip", parsed.safeFilename());
		assertEquals("EREF-2026-001", parsed.displayEref());
	}

	@Test
	void forwardSlashPathRetainsOnlyFinalSegment() {
		ArchiveFilenameParser.ParsedFilename parsed = ArchiveFilenameParser.parse("/tmp/dir/EREF-2026-001.zip");
		assertEquals("EREF-2026-001.zip", parsed.safeFilename());
		assertEquals("EREF-2026-001", parsed.displayEref());
	}

	@Test
	void parentTraversalSegmentsAreDiscarded() {
		ArchiveFilenameParser.ParsedFilename parsed = ArchiveFilenameParser.parse("../../EREF-2026-001.zip");
		assertEquals("EREF-2026-001.zip", parsed.safeFilename());
		assertEquals("EREF-2026-001", parsed.displayEref());
	}

	@Test
	void nullAndBlankNamesAreRejected() {
		assertThrows(ArchiveValidationException.class, () -> ArchiveFilenameParser.parse(null));
		assertThrows(ArchiveValidationException.class, () -> ArchiveFilenameParser.parse("   "));
	}

	@Test
	void bareZipSuffixIsRejected() {
		ArchiveValidationException e =
				assertThrows(ArchiveValidationException.class, () -> ArchiveFilenameParser.parse(".zip"));
		assertEquals(ArchiveValidationException.Category.INVALID_ARCHIVE_FILENAME, e.getCategory());
	}

	@Test
	void wrongSuffixIsRejected() {
		ArchiveValidationException e =
				assertThrows(ArchiveValidationException.class, () -> ArchiveFilenameParser.parse("archive.pdf"));
		assertEquals(ArchiveValidationException.Category.INVALID_ARCHIVE_FILENAME, e.getCategory());
	}

	@Test
	void dotAndDotDotSegmentsAreRejected() {
		assertThrows(ArchiveValidationException.class, () -> ArchiveFilenameParser.parse("dir/.."));
		assertThrows(ArchiveValidationException.class, () -> ArchiveFilenameParser.parse("dir/."));
	}

	@Test
	void nameLongerThan256CodePointsIsRejected() {
		String longName = "a".repeat(257) + ".zip";
		ArchiveValidationException e =
				assertThrows(ArchiveValidationException.class, () -> ArchiveFilenameParser.parse(longName));
		assertEquals(ArchiveValidationException.Category.INVALID_ARCHIVE_FILENAME, e.getCategory());
	}

	@Test
	void nameOfExactly256CodePointsIsAccepted() {
		String name = "a".repeat(256) + ".zip";
		ArchiveFilenameParser.ParsedFilename parsed = ArchiveFilenameParser.parse(name);
		assertEquals(256, parsed.displayEref().codePointCount(0, parsed.displayEref().length()));
	}

	@Test
	void baseThatNormalizesToEmptyIsRejected() {
		ArchiveValidationException e =
				assertThrows(ArchiveValidationException.class, () -> ArchiveFilenameParser.parse("!!!.zip"));
		assertEquals(ArchiveValidationException.Category.INVALID_ARCHIVE_FILENAME, e.getCategory());
	}

	@Test
	void exceptionMessagesNeverContainTheFilename() {
		String secret = "SECRET-EREF-001";
		ArchiveValidationException e = assertThrows(ArchiveValidationException.class,
				() -> ArchiveFilenameParser.parse(secret + ".pdf"));
		assertTrue(!e.getMessage().toLowerCase(Locale.ROOT).contains(secret),
				"exception message must not echo the submitted filename");

		ArchiveValidationException e2 =
				assertThrows(ArchiveValidationException.class, () -> ArchiveFilenameParser.parse("!!!.zip"));
		assertTrue(!e2.getMessage().toLowerCase(Locale.ROOT).contains("!!!"),
				"exception message must not echo the submitted filename");
	}

}
