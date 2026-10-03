package com.whatsappclone.backend.media.dto;

import java.time.Instant;

public record DownloadUrlResponse(String downloadUrl, long expiresInSeconds, Instant expiresAt) {
}
