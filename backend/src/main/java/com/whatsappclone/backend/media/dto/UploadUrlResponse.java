package com.whatsappclone.backend.media.dto;

import java.time.Instant;
import java.util.Map;

/**
 * @param objectKey pass this as {@code mediaKey} when sending the message; never expose it as a URL
 * @param uploadUrl PUT the bytes here with {@code headers} and nothing else — S3 rejects a
 * mismatched {@code Content-Type} because the value is part of the signature
 */
public record UploadUrlResponse(String objectKey, String uploadUrl, Map<String, String> headers,
		long expiresInSeconds, Instant expiresAt) {
}
