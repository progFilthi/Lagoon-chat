package com.whatsappclone.backend.media.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Confirms an upload landed, and re-checks it against what was declared. */
public record CompleteUploadRequest(

		@NotBlank(message = "objectKey is required")
		@Size(max = 512, message = "objectKey must be at most 512 characters")
		String objectKey) {
}
