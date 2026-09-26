package horse.sumomo.pos_doc_backend.content;

public class SearchPageArchiveException extends RuntimeException {
	public enum Code {
		INVALID_PAGE(400, "INVALID_PAGE_ARCHIVE_REQUEST", "Select 1 to 20 unique records from the displayed page."),
		PAGE_CHANGED(409, "PAGE_CHANGED", "A displayed record is no longer available."),
		MISSING_EREF(409, "MISSING_EREF", "A displayed record has no eRef."),
		TOO_LARGE(413, "PAGE_ARCHIVE_TOO_LARGE", "The displayed page exceeds the download size limit."),
		FAILED(500, "PAGE_ARCHIVE_FAILED", "The page download could not be prepared.");
		public final int status;
		public final String code;
		public final String detail;
		Code(int status, String code, String detail) {
			this.status = status; this.code = code; this.detail = detail;
		}
	}
	private final Code code;
	public SearchPageArchiveException(Code code) { super(code.detail); this.code = code; }
	public SearchPageArchiveException(Code code, Throwable cause) { super(code.detail, cause); this.code = code; }
	public Code getCode() { return code; }
}
