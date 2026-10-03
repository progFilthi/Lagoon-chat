package com.whatsappclone.backend.media;

import com.whatsappclone.backend.common.api.ApiResponse;
import com.whatsappclone.backend.media.dto.CompleteUploadRequest;
import com.whatsappclone.backend.media.dto.CompleteUploadResponse;
import com.whatsappclone.backend.media.dto.DownloadUrlRequest;
import com.whatsappclone.backend.media.dto.DownloadUrlResponse;
import com.whatsappclone.backend.media.dto.UploadUrlRequest;
import com.whatsappclone.backend.media.dto.UploadUrlResponse;
import com.whatsappclone.backend.security.resolver.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/media")
public class MediaController {

	private final MediaService mediaService;

	public MediaController(MediaService mediaService) {
		this.mediaService = mediaService;
	}

	/** Step 1 of an upload: get a signed URL, PUT the bytes to it directly, then confirm. */
	@PostMapping("/upload-url")
	public ApiResponse<UploadUrlResponse> uploadUrl(@CurrentUser UUID currentUserId,
			@Valid @RequestBody UploadUrlRequest request) {
		return ApiResponse.ok(mediaService.createUploadUrl(currentUserId, request));
	}

	/** Step 3: verifies the stored object, and deletes it if it contradicts the declaration. */
	@PostMapping("/complete")
	public ApiResponse<CompleteUploadResponse> complete(@CurrentUser UUID currentUserId,
			@Valid @RequestBody CompleteUploadRequest request) {
		return ApiResponse.ok(mediaService.completeUpload(currentUserId, request.objectKey()));
	}

	/** Signed GET for a message's attachment. Authorised by chat membership, not by key. */
	@PostMapping("/download-url")
	public ApiResponse<DownloadUrlResponse> downloadUrl(@CurrentUser UUID currentUserId,
			@Valid @RequestBody DownloadUrlRequest request) {
		return ApiResponse.ok(mediaService.createDownloadUrl(currentUserId, request.messageId()));
	}
}