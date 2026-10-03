package com.whatsappclone.backend.media.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * @param contentType must be one of the configured allowlist entries; the value is bound into the
 * presigned signature, so S3 rejects an upload whose header does not match it
 * @param sizeBytes declared up front so an obviously-too-large upload never gets a URL. A
 * presigned PUT cannot enforce a byte range, so this is a client courtesy rather than a guarantee —
 * {@code POST /api/media/complete} re-checks the stored object and deletes it if it does not hold up
 */
public record UploadUrlRequest(

		@NotBlank(message = "contentType is required")
		@Size(max = 100, message = "contentType must be at most 100 characters")
		String contentType,

		@NotNull(message = "sizeBytes is required")
		@Positive(message = "sizeBytes must be positive")
		Long sizeBytes) {
}