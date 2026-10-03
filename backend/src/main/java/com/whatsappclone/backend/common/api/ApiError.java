package com.whatsappclone.backend.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String code, String message, Object details) {

	public static ApiError of(String code, String message) {
		return new ApiError(code, message, null);
	}

	public static ApiError of(String code, String message, Object details) {
		return new ApiError(code, message, details);
	}
}