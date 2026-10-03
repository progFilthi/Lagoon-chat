package com.whatsappclone.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
		@NotBlank(message = "phoneNumber is required")
		@Pattern(regexp = "^\\+[1-9]\\d{7,14}$", message = "phoneNumber must be E.164, e.g. +14155550100")
		String phoneNumber,

		@NotBlank(message = "username is required")
		@Size(min = 3, max = 32, message = "username must be between 3 and 32 characters")
		@Pattern(regexp = "^[a-zA-Z0-9_.]+$", message = "username may only contain letters, digits, dot and underscore")
		String username,

		@NotBlank(message = "password is required")
		@Size(min = 8, max = 72, message = "password must be between 8 and 72 characters")
		String password) {
}