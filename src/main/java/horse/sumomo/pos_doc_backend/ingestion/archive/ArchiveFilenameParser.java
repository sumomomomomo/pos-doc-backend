package horse.sumomo.pos_doc_backend.ingestion.archive;

import java.util.Locale;

import horse.sumomo.pos_doc_backend.persistence.normalization.MetadataNormalizer;

/**
 * Parses the multipart original filename into safe, untrusted-but-bounded
 * intake metadata.
 *
 * <p>The supplied name is treated as untrusted input. Only the final path
 * segment is retained (after normalizing backslashes to slashes), which
 * handles browser-supplied paths such as {@code C:\fakepath\EREF-001.zip}
 * without trusting any directory component. The archive is never extracted
 * using the submitted paths.
 *
 * <p>Rules, in order: reject null/blank; reduce to the final segment;
 * reject a blank, {@code .}, or {@code ..} segment; require a
 * case-insensitive {@code .zip} suffix; remove only that final suffix; trim
 * the base name; require 1..256 Unicode code points; and normalize with
 * {@link MetadataNormalizer#normalizeIdentifier(String)}.
 *
 * <p>Exception messages never contain the submitted filename or eRef.
 */
public final class ArchiveFilenameParser {

	private static final int MAX_EREF_CODE_POINTS = 256;
	private static final String ZIP_SUFFIX = ".zip";

	private ArchiveFilenameParser() {
	}

	/**
	 * Result of filename parsing.
	 *
	 * @param safeFilename the retained final path segment including {@code .zip}
	 * @param displayEref  the trimmed base name without the {@code .zip} suffix
	 * @param normalizedEref the NFKC/uppercase identifier-normalized eRef
	 */
	public record ParsedFilename(String safeFilename, String displayEref, String normalizedEref) {
	}

	public static ParsedFilename parse(String originalFilename) {
		if (originalFilename == null || originalFilename.isBlank()) {
			throw new ArchiveValidationException(ArchiveValidationException.Category.INVALID_ARCHIVE_FILENAME,
					"original filename is missing or blank");
		}

		String normalized = originalFilename.replace('\\', '/');
		int lastSlash = normalized.lastIndexOf('/');
		String segment = lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized;

		if (segment.isBlank() || segment.equals(".") || segment.equals("..")) {
			throw new ArchiveValidationException(ArchiveValidationException.Category.INVALID_ARCHIVE_FILENAME,
					"filename has no usable final segment");
		}

		if (!segment.toLowerCase(Locale.ROOT).endsWith(ZIP_SUFFIX)) {
			throw new ArchiveValidationException(ArchiveValidationException.Category.INVALID_ARCHIVE_FILENAME,
					"filename must end with .zip");
		}

		String base = segment.substring(0, segment.length() - ZIP_SUFFIX.length()).trim();
		if (base.codePointCount(0, base.length()) < 1
				|| base.codePointCount(0, base.length()) > MAX_EREF_CODE_POINTS) {
			throw new ArchiveValidationException(ArchiveValidationException.Category.INVALID_ARCHIVE_FILENAME,
					"eRef derived from the filename has an invalid length");
		}

		String normalizedEref;
		try {
			normalizedEref = MetadataNormalizer.normalizeIdentifier(base);
		}
		catch (IllegalArgumentException e) {
			throw new ArchiveValidationException(
					ArchiveValidationException.Category.INVALID_ARCHIVE_FILENAME,
					"eRef derived from the filename is empty after normalization");
		}

		return new ParsedFilename(segment, base, normalizedEref);
	}

}
