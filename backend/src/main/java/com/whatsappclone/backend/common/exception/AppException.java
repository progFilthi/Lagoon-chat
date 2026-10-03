package com.whatsappclone.backend.common.exception;

import com.whatsappclone.backend.common.api.ApiError;

public class AppException extends RuntimeException {

	private final ErrorCode errorCode;
	private final transient Object details;

	public AppException(ErrorCode errorCode) {
		this(errorCode, errorCode.name(), null);
	}

	public AppException(ErrorCode errorCode, String message) {
		this(errorCode, message, null);
	}

	public AppException(ErrorCode errorCode, String message, Object details) {
		super(message);
		this.errorCode = errorCode;
		this.details = details;
	}

	public ErrorCode getErrorCode() {
		return errorCode;
	}

	public Object getDetails() {
		return details;
	}

	public ApiError toApiError() {
		return ApiError.of(errorCode.name(), getMessage(), details);
	}
}