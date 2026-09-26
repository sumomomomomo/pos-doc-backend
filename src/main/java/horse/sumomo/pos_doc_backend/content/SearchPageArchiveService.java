package horse.sumomo.pos_doc_backend.content;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.stereotype.Service;

import horse.sumomo.pos_doc_backend.persistence.entity.PosDocumentEntity;
import horse.sumomo.pos_doc_backend.persistence.entity.PosRecordEntity;
import horse.sumomo.pos_doc_backend.persistence.repository.PosDocumentRepository;
import horse.sumomo.pos_doc_backend.persistence.repository.PosRecordRepository;

/** Builds a complete archive before any response bytes are sent. */
@Service
public class SearchPageArchiveService {
	private static final long MAX_BYTES = 512L * 1024 * 1024;
	private final PosRecordRepository records;
	private final PosDocumentRepository documents;
	private final DocumentContentService content;
	private final Path directory;
	private final String filePrefix = "page-" + ProcessHandle.current().pid() + "-";

	public SearchPageArchiveService(PosRecordRepository records, PosDocumentRepository documents,
			DocumentContentService content) throws IOException {
		this.records = records;
		this.documents = documents;
		this.content = content;
		this.directory = Path.of(System.getProperty("java.io.tmpdir"), "pos-search-page-archives");
		Files.createDirectories(directory);
		try (var stale = Files.list(directory)) {
			stale.filter(SearchPageArchiveService::belongsToStoppedProcess).forEach(p -> {
				try { Files.deleteIfExists(p); } catch (IOException ignored) { /* retry on next startup */ }
			});
		}
	}

	public Path create(List<UUID> ids) {
		if (ids == null || ids.isEmpty() || ids.size() > 20 || ids.stream().anyMatch(id -> id == null)
				|| new HashSet<>(ids).size() != ids.size()) {
			throw new SearchPageArchiveException(SearchPageArchiveException.Code.INVALID_PAGE);
		}
		Path file = null;
		try {
			file = Files.createTempFile(directory, filePrefix, ".zip");
			try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
				BoundedOutputStream bounded = new BoundedOutputStream(zip, MAX_BYTES);
				Set<String> folders = new HashSet<>();
				long expectedBytes = 0;
				for (UUID id : ids) {
					PosRecordEntity record = records.findByIdAndDeletedAtIsNull(id)
							.orElseThrow(() -> new SearchPageArchiveException(SearchPageArchiveException.Code.PAGE_CHANGED));
					String eref = record.getErefNumber();
					if (eref == null || eref.isBlank()) throw new SearchPageArchiveException(SearchPageArchiveException.Code.MISSING_EREF);
					String folderName = safeName(eref);
					if (folderName.isBlank()) {
						throw new SearchPageArchiveException(SearchPageArchiveException.Code.MISSING_EREF);
					}
					String folder = unique(folderName, folders) + "/";
					zip.putNextEntry(new ZipEntry(folder));
					zip.closeEntry();
					Set<String> filenames = new HashSet<>();
					for (PosDocumentEntity document : documents.findByPosRecordIdOrderBySequenceNumberAsc(id)) {
						ContentDescriptor descriptor;
						try {
							descriptor = content.pdfDescriptor(id, document.getId());
						} catch (DocumentContentException e) {
							if (e.getCode() == DocumentContentException.Code.DOCUMENT_NOT_FOUND) {
								throw new SearchPageArchiveException(SearchPageArchiveException.Code.PAGE_CHANGED, e);
							}
							throw e;
						}
						expectedBytes += descriptor.expectedByteSize();
						if (expectedBytes > MAX_BYTES) throw new SearchPageArchiveException(SearchPageArchiveException.Code.TOO_LARGE);
						String name = safeName(descriptor.originalFilename());
						if (name.isBlank()) {
							name = "document.pdf";
						}
						if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) {
							name += ".pdf";
						}
						name = unique(name, filenames);
						zip.putNextEntry(new ZipEntry(folder + name));
						long before = bounded.count();
						content.streamContent(descriptor, bounded);
						if (bounded.count() - before != descriptor.expectedByteSize()) {
							throw new SearchPageArchiveException(SearchPageArchiveException.Code.FAILED);
						}
						zip.closeEntry();
					}
				}
			}
			if (Files.size(file) > MAX_BYTES) {
				throw new SearchPageArchiveException(SearchPageArchiveException.Code.TOO_LARGE);
			}
			return file;
		} catch (IOException | RuntimeException e) {
			if (file != null) {
				try { Files.deleteIfExists(file); } catch (IOException ignored) { /* retained for startup cleanup */ }
			}
			if (e instanceof SearchPageArchiveException known) throw known;
			if (e instanceof DocumentContentException known) throw known;
			throw new SearchPageArchiveException(SearchPageArchiveException.Code.FAILED, e);
		}
	}

	private static String safeName(String value) {
		if (value == null) return "";
		String clean = value.replaceAll("[<>:\"|?*\\\\/\\p{Cntrl}]", "_")
				.replace("..", "_").trim().replaceAll("[. ]+$", "");
		if (clean.matches("(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?$")) {
			clean = "_" + clean;
		}
		return clean;
	}

	private static boolean belongsToStoppedProcess(Path path) {
		String name = path.getFileName().toString();
		if (!name.startsWith("page-") || !name.endsWith(".zip")) return false;
		int end = name.indexOf('-', 5);
		if (end < 0) return false;
		try {
			long pid = Long.parseLong(name.substring(5, end));
			return ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true);
		} catch (NumberFormatException e) {
			return false;
		}
	}

	private static String unique(String name, Set<String> used) {
		String candidate = name;
		int index = 2;
		while (!used.add(candidate.toLowerCase(java.util.Locale.ROOT))) {
			int dot = name.lastIndexOf('.');
			candidate = dot > 0 ? name.substring(0, dot) + "-" + index++ + name.substring(dot) : name + "-" + index++;
		}
		return candidate;
	}

	private static final class BoundedOutputStream extends java.io.OutputStream {
		private final java.io.OutputStream delegate;
		private final long limit;
		private long count;
		long count() { return count; }
		BoundedOutputStream(java.io.OutputStream delegate, long limit) { this.delegate = delegate; this.limit = limit; }
		@Override public void write(int value) throws IOException {
			if (count >= limit) throw new SearchPageArchiveException(SearchPageArchiveException.Code.TOO_LARGE);
			count++;
			delegate.write(value);
		}
		@Override public void write(byte[] bytes, int offset, int length) throws IOException {
			if (count + length > limit) throw new SearchPageArchiveException(SearchPageArchiveException.Code.TOO_LARGE);
			count += length;
			delegate.write(bytes, offset, length);
		}
	}
}
