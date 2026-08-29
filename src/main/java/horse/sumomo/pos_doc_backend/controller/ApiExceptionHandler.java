package horse.sumomo.pos_doc_backend.controller;

import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.yourcompany.pos.api.model.Problem;
import com.yourcompany.pos.api.model.ProblemFieldErrorsInner;

import horse.sumomo.pos_doc_backend.ingestion.archive.ArchiveValidationException;
import horse.sumomo.pos_doc_backend.ingestion.application.IntakeException;

/**
 * Maps intake and HTTP failures to the generated {@link Problem} DTO with
 * the {@code application/problem+json} media type.
 *
 * <p>Problem bodies carry only the stable machine-readable {@code code},
 * a fixed user-safe {@code detail}, and the HTTP status. They never contain
 * the submitted filename, eRef or policy number, entry name, temp path,
 * MinIO key, SHA-256, database or broker exception text, credentials, or a
 * stack trace. Field-level validation errors report only the field name
 * with a fixed generic message, never the submitted value.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	private static final MediaType PROBLEM_JSON = MediaType.parseMediaType("application/problem+json");

	@ExceptionHandler(IntakeException.class)
	public ResponseEntity<Problem> handleIntake(IntakeException e) {
		IntakeException.Code code = e.getCode();
		log.warn("Request rejected (category={})", code.code());
		return problem(code.httpStatus(), code.code(), code.detail());
	}

	@ExceptionHandler(ArchiveValidationException.class)
	public ResponseEntity<Problem> handleArchiveValidation(ArchiveValidationException e) {
		IntakeException.Code code = switch (e.getCategory()) {
			case EMPTY_UPLOAD -> IntakeException.Code.MISSING_FILE;
			case ARCHIVE_TOO_LARGE -> IntakeException.Code.ARCHIVE_TOO_LARGE;
			case UNSUPPORTED_ARCHIVE_TYPE -> IntakeException.Code.UNSUPPORTED_ARCHIVE_TYPE;
			case INVALID_ARCHIVE -> IntakeException.Code.INVALID_ARCHIVE;
			case INVALID_ARCHIVE_FILENAME -> IntakeException.Code.INVALID_ARCHIVE_FILENAME;
		};
		log.warn("Request rejected (category={})", code.code());
		return problem(code.httpStatus(), code.code(), code.detail());
	}

	@ExceptionHandler(MissingServletRequestPartException.class)
	public ResponseEntity<Problem> handleMissingPart(MissingServletRequestPartException e) {
		log.warn("Request rejected (category=missing-file)");
		return problem(IntakeException.Code.MISSING_FILE.httpStatus(),
				IntakeException.Code.MISSING_FILE.code(), IntakeException.Code.MISSING_FILE.detail());
	}

	@ExceptionHandler(ConstraintViolationException.class)
	public ResponseEntity<Problem> handleConstraintViolation(ConstraintViolationException e) {
		log.warn("Request rejected (category=constraint-violation)");
		boolean policyViolation = e.getConstraintViolations().stream()
				.anyMatch(v -> v.getPropertyPath().toString().contains("policyNumber"));
		if (policyViolation) {
			return problem(HttpStatus.BAD_REQUEST.value(), "INVALID_POLICY_NUMBER",
					"The policy number is blank or invalid.");
		}
		return problem(HttpStatus.BAD_REQUEST.value(), "INVALID_REQUEST", "Malformed request or invalid parameter.");
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<Problem> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
		log.warn("Request rejected (category=invalid-request)");
		Problem problem = new Problem("about:blank", "Error", HttpStatus.BAD_REQUEST.value())
				.code("INVALID_REQUEST")
				.detail("Malformed request or invalid parameter.");
		for (FieldError fieldError : e.getBindingResult().getFieldErrors()) {
			problem.addFieldErrorsItem(new ProblemFieldErrorsInner()
					.field(fieldError.getField())
					.message("invalid value"));
		}
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(PROBLEM_JSON).body(problem);
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<Problem> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
		log.warn("Request rejected (category=invalid-path-parameter)");
		return problem(HttpStatus.BAD_REQUEST.value(), "INVALID_REQUEST", "Malformed request or invalid parameter.");
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<Problem> handleUnreadableBody(HttpMessageNotReadableException e) {
		log.warn("Request rejected (category=unreadable-body)");
		return problem(HttpStatus.BAD_REQUEST.value(), "INVALID_REQUEST", "Malformed request or invalid parameter.");
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	public ResponseEntity<Problem> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException e) {
		log.warn("Request rejected (category=unsupported-media-type)");
		return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE.value(), "UNSUPPORTED_ARCHIVE_TYPE",
				"The uploaded file is not a supported ZIP archive.");
	}

	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<Problem> handleNotFound(NoResourceFoundException e) {
		return problem(HttpStatus.NOT_FOUND.value(), "NOT_FOUND", "The requested resource does not exist.");
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<Problem> handleUnexpected(Exception e) {
		// Log the raw exception server-side only; the client receives a
		// sanitized body.
		log.error("Unexpected error while handling request", e);
		return problem(IntakeException.Code.INGESTION_INTAKE_FAILED.httpStatus(),
				IntakeException.Code.INGESTION_INTAKE_FAILED.code(),
				IntakeException.Code.INGESTION_INTAKE_FAILED.detail());
	}

	private static ResponseEntity<Problem> problem(int status, String code, String detail) {
		Problem problem = new Problem("about:blank", "Error", status).code(code).detail(detail);
		return ResponseEntity.status(status).contentType(PROBLEM_JSON).body(problem);
	}

}
