package horse.sumomo.pos_doc_backend.rendering.service;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Strategy interface for creating temporary files. Production uses the
 * default system temp directory; tests can inject a test-owned directory
 * to isolate temp file creation from the shared JVM temp directory.
 */
@FunctionalInterface
public interface TempFileFactory {

	/**
	 * Creates a new temporary file with the given prefix and suffix in
	 * the factory's configured directory.
	 *
	 * @param prefix the file name prefix
	 * @param suffix the file name suffix (may be null)
	 * @return the path of the created temp file
	 * @throws IOException if the file cannot be created
	 */
	Path createTempFile(String prefix, String suffix) throws IOException;

	/**
	 * Default implementation that uses the system temp directory.
	 */
	static TempFileFactory systemDefault() {
		return (prefix, suffix) -> java.nio.file.Files.createTempFile(prefix, suffix);
	}

	/**
	 * Creates a factory that writes temp files into the given directory.
	 *
	 * @param dir the directory to create temp files in
	 * @return a factory bound to that directory
	 */
	static TempFileFactory inDirectory(java.nio.file.Path dir) {
		return (prefix, suffix) -> java.nio.file.Files.createTempFile(dir, prefix, suffix);
	}

}
