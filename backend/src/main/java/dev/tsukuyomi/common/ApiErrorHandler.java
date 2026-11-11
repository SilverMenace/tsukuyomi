package dev.tsukuyomi.common;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns exceptions into one JSON shape: {"error": {"code": "...", "message": "..."}}.
 * Unexpected errors are logged in full but the client only sees "internal_error",
 * so stack traces and SQL never leak into responses.
 */
@RestControllerAdvice
public class ApiErrorHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

	public static Map<String, Object> body(String code, String message) {
		return Map.of("error", Map.of("code", code, "message", message));
	}

	@ExceptionHandler(ApiException.class)
	ResponseEntity<Map<String, Object>> handle(ApiException e) {
		return ResponseEntity.status(e.status()).body(body(e.code(), e.getMessage()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<Map<String, Object>> handle(MethodArgumentNotValidException e) {
		String message = e.getBindingResult().getFieldErrors().stream()
			.map(f -> f.getField() + ": " + f.getDefaultMessage())
			.findFirst()
			.orElse("invalid request");
		return ResponseEntity.badRequest().body(body("invalid_request", message));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<Map<String, Object>> handle(HttpMessageNotReadableException e) {
		// Our own validation can fire while JSON is being read (e.g. an unknown
		// column type). In that case the more specific error is the useful one.
		for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
			if (cause instanceof ApiException api) {
				return handle(api);
			}
		}
		return ResponseEntity.badRequest().body(body("invalid_json", "request body is not valid JSON for this endpoint"));
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<Map<String, Object>> handle(Exception e) throws Exception {
		// Let Spring handle its own exceptions (404 for unknown paths, 405, ...).
		if (e instanceof org.springframework.web.ErrorResponse) {
			throw e;
		}
		log.error("unhandled error", e);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
			.body(body("internal_error", "something went wrong on our side"));
	}

}
