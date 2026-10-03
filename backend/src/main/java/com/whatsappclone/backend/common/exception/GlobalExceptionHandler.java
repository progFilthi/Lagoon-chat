package com.whatsappclone.backend.common.exception;

import com.whatsappclone.backend.common.api.ApiError;
import com.whatsappclone.backend.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(AppException.class)
	public ResponseEntity<ApiResponse<Void>> handleAppException(AppException ex) {
		return ResponseEntity.status(ex.getErrorCode().status()).body(ApiResponse.failure(ex.toApiError()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
		Map<String, String> fieldErrors = new LinkedHashMap<>();
		ex.getBindingResult().getFieldErrors()
				.forEach(fe -> fieldErrors.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
		ex.getBindingResult().getGlobalErrors()
				.forEach(ge -> fieldErrors.putIfAbsent(ge.getObjectName(), ge.getDefaultMessage()));
		ApiError error = ApiError.of(ErrorCode.VALIDATION_ERROR.name(), "Request validation failed", fieldErrors);
		return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.status()).body(ApiResponse.failure(error));
	}

	@ExceptionHandler(ConstraintViolationException.class)
	public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException ex) {
		Map<String, String> fieldErrors = new LinkedHashMap<>();
		ex.getConstraintViolations()
				.forEach(v -> fieldErrors.putIfAbsent(String.valueOf(v.getPropertyPath()), v.getMessage()));
		ApiError error = ApiError.of(ErrorCode.VALIDATION_ERROR.name(), "Request validation failed", fieldErrors);
		return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.status()).body(ApiResponse.failure(error));
	}

	@ExceptionHandler({ HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
			MethodArgumentTypeMismatchException.class })
	public ResponseEntity<ApiResponse<Void>> handleMalformed(Exception ex) {
		ApiError error = ApiError.of(ErrorCode.MALFORMED_REQUEST.name(), "Request could not be parsed");
		return ResponseEntity.status(ErrorCode.MALFORMED_REQUEST.status()).body(ApiResponse.failure(error));
	}

	@ExceptionHandler(AccessDeniedException.class)
	public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
		ApiError error = ApiError.of(ErrorCode.FORBIDDEN.name(), "Access is denied");
		return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.failure(error));
	}

	@ExceptionHandler(AuthenticationException.class)
	public ResponseEntity<ApiResponse<Void>> handleAuthentication(AuthenticationException ex) {
		ApiError error = ApiError.of(ErrorCode.UNAUTHORIZED.name(), "Authentication required");
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.failure(error));
	}

	@ExceptionHandler(NoHandlerFoundException.class)
	public ResponseEntity<ApiResponse<Void>> handleNoHandler(NoHandlerFoundException ex) {
		ApiError error = ApiError.of(ErrorCode.NOT_FOUND.name(),
				"No endpoint " + ex.getHttpMethod() + " " + ex.getRequestURL());
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure(error));
	}

	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException ex) {
		ApiError error = ApiError.of(ErrorCode.NOT_FOUND.name(), "No endpoint " + ex.getHttpMethod() + " "
				+ ex.getResourcePath());
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure(error));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex, HttpServletRequest request) {
		log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
		ApiError error = ApiError.of(ErrorCode.INTERNAL_ERROR.name(), "Unexpected server error");
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.failure(error));
	}
}