package com.whatsappclone.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(

		@NotBlank(message = "phoneNumber is required") String phoneNumber,

		@NotBlank(message = "password is required") String password) {
}