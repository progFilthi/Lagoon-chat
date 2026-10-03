package com.whatsappclone.backend.media.dto;

/**
 * @param verified false when the stored object contradicted the declared content type or size and
 * has been deleted; the message must not be sent in that case
 */
public record CompleteUploadResponse(boolean verified, Long sizeBytes, String contentType, String reason) {

	public static CompleteUploadResponse rejected(String reason) {
		return new CompleteUploadResponse(false, null, null, reason);
	}

	public static CompleteUploadResponse ok(Long sizeBytes, String contentType) {
		return new CompleteUploadResponse(true, sizeBytes, contentType, null);
	}
}
