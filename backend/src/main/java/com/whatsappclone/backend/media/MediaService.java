package com.whatsappclone.backend.media;

import com.whatsappclone.backend.chat.repository.ChatParticipantRepository;
import com.whatsappclone.backend.common.exception.AppException;
import com.whatsappclone.backend.common.exception.ErrorCode;
import com.whatsappclone.backend.media.dto.CompleteUploadResponse;
import com.whatsappclone.backend.media.dto.DownloadUrlResponse;
import com.whatsappclone.backend.media.dto.UploadUrlRequest;
import com.whatsappclone.backend.media.dto.UploadUrlResponse;
import com.whatsappclone.backend.message.model.Message;
import com.whatsappclone.backend.message.repository.MessageRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * Media lives in a private S3 bucket and is never proxied through this service. A client asks for a
 * short-lived signed URL, transfers the bytes directly, and only the object key is persisted on the
 * message. Three consequences worth knowing:
 *
 * <ul>
 * <li>No AWS credential ever reaches a browser. The server signs; the client holds a URL already
 * scoped to one key and one verb.
 * <li>Uploads and downloads scale independently of this service, because the bytes never touch it.
 * <li>A URL is a bearer token for a single object until it expires, so both TTLs are deliberately
 * short and the download one is not cache-friendly.
 * </ul>
 */
@Service
public class MediaService {

	private static final String KEY_ROOT = "media/";

	private final S3Client s3Client;
	private final S3Presigner s3Presigner;
	private final MessageRepository messageRepository;
	private final ChatParticipantRepository chatParticipantRepository;
	private final String bucket;
	private final Duration uploadUrlTtl;
	private final Duration downloadUrlTtl;
	private final long maxBytes;
	private final Map<String, String> extensionsByContentType;

	public MediaService(S3Client s3Client, S3Presigner s3Presigner, MessageRepository messageRepository,
			ChatParticipantRepository chatParticipantRepository, @Value("${app.media.bucket}") String bucket,
			@Value("${app.media.upload-url-ttl}") Duration uploadUrlTtl,
			@Value("${app.media.download-url-ttl}") Duration downloadUrlTtl,
			@Value("${app.media.max-bytes}") long maxBytes,
			@Value("${app.media.allowed-content-types}") String allowedContentTypes) {
		this.s3Client = s3Client;
		this.s3Presigner = s3Presigner;
		this.messageRepository = messageRepository;
		this.chatParticipantRepository = chatParticipantRepository;
		this.bucket = bucket;
		this.uploadUrlTtl = uploadUrlTtl;
		this.downloadUrlTtl = downloadUrlTtl;
		this.maxBytes = maxBytes;
		this.extensionsByContentType = parseAllowlist(allowedContentTypes);
	}

	/**
	 * Issues a PUT URL for a key under the caller's own prefix. The content type is signed into the
	 * request, so S3 rejects the upload if the client sends a different {@code Content-Type} header.
	 */
	public UploadUrlResponse createUploadUrl(UUID userId, UploadUrlRequest request) {
		requireConfigured();
		String contentType = normalise(request.contentType());
		String extension = extensionsByContentType.get(contentType);
		if (extension == null) {
			throw new AppException(ErrorCode.VALIDATION_ERROR,
					"contentType must be one of " + String.join(", ", extensionsByContentType.keySet()));
		}
		if (request.sizeBytes() > maxBytes) {
			throw new AppException(ErrorCode.VALIDATION_ERROR,
					"file is too large; the limit is " + maxBytes + " bytes");
		}

		String objectKey = KEY_ROOT + userId + "/" + UUID.randomUUID() + "." + extension;
		PutObjectRequest put = PutObjectRequest.builder()
				.bucket(bucket)
				.key(objectKey)
				.contentType(contentType)
				.build();
		PresignedPutObjectRequest presigned = s3Presigner.presignPutObject(PutObjectPresignRequest.builder()
				.signatureDuration(uploadUrlTtl)
				.putObjectRequest(put)
				.build());

		Instant expiresAt = Instant.now().plus(uploadUrlTtl);
		return new UploadUrlResponse(objectKey, presigned.url().toString(),
				Map.of("Content-Type", contentType), uploadUrlTtl.toSeconds(), expiresAt);
	}

	/**
	 * Confirms the object exists and matches what was declared, deleting it if not.
	 *
	 * <p>This exists because a presigned PUT cannot carry a byte-range condition, so the size check
	 * in {@link #createUploadUrl} is only advisory. Without this step a client could declare 1 KB and
	 * store an arbitrarily large object.
	 */
	public CompleteUploadResponse completeUpload(UUID userId, String objectKey) {
		requireConfigured();
		requireOwnedBy(userId, objectKey);

		HeadObjectResponse head;
		try {
			head = s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey).build());
		}
		catch (NoSuchKeyException ex) {
			return CompleteUploadResponse.rejected("no object was uploaded at that key");
		}
		catch (S3Exception ex) {
			if (ex.statusCode() == 404) {
				return CompleteUploadResponse.rejected("no object was uploaded at that key");
			}
			throw new AppException(ErrorCode.INTERNAL_ERROR, "Could not read the uploaded object");
		}

		String contentType = normalise(head.contentType());
		if (!extensionsByContentType.containsKey(contentType)) {
			delete(objectKey);
			return CompleteUploadResponse.rejected("stored content type is not allowed: " + contentType);
		}
		if (head.contentLength() != null && head.contentLength() > maxBytes) {
			delete(objectKey);
			return CompleteUploadResponse.rejected("stored object exceeds the size limit of " + maxBytes + " bytes");
		}
		return CompleteUploadResponse.ok(head.contentLength(), contentType);
	}

	/**
	 * Signs a GET for a message's object. Authorisation is deliberately by message: the caller must
	 * belong to the chat that the message is in. Accepting a bare object key would mean anyone who
	 * ever learned a key could read that object directly.
	 */
	@Transactional(readOnly = true)
	public DownloadUrlResponse createDownloadUrl(UUID userId, UUID messageId) {
		requireConfigured();
		Message message = messageRepository.findById(messageId)
				.orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Message not found"));
		chatParticipantRepository.findByChatIdAndUserId(message.getChat().getId(), userId)
				.orElseThrow(() -> new AppException(ErrorCode.NOT_CHAT_PARTICIPANT,
						"You are not a member of this chat"));
		if (!message.isMedia()) {
			throw new AppException(ErrorCode.VALIDATION_ERROR, "That message has no attachment");
		}

		PresignedGetObjectRequest presigned = s3Presigner.presignGetObject(GetObjectPresignRequest.builder()
				.signatureDuration(downloadUrlTtl)
				.getObjectRequest(GetObjectRequest.builder()
						.bucket(bucket)
						.key(message.getMediaKey())
						.build())
				.build());

		return new DownloadUrlResponse(presigned.url().toString(), downloadUrlTtl.toSeconds(),
				Instant.now().plus(downloadUrlTtl));
	}

	/** True when the key sits under the caller's own prefix, matching how keys are issued. */
	private void requireOwnedBy(UUID userId, String objectKey) {
		String prefix = KEY_ROOT + userId + "/";
		if (objectKey == null || !objectKey.startsWith(prefix) || objectKey.contains("..")) {
			throw new AppException(ErrorCode.VALIDATION_ERROR, "objectKey was not issued to you");
		}
	}

	private void requireConfigured() {
		if (!MediaConfig.isValidBucketName(bucket)) {
			throw new AppException(ErrorCode.MEDIA_NOT_CONFIGURED,
					"Media uploads are not configured; set MEDIA_BUCKET to an S3 bucket name");
		}
	}

	private void delete(String objectKey) {
		s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey).build());
	}

	private static String normalise(String contentType) {
		if (contentType == null) {
			return "";
		}
		int parameters = contentType.indexOf(';');
		return (parameters >= 0 ? contentType.substring(0, parameters) : contentType)
				.trim()
				.toLowerCase(Locale.ROOT);
	}

	/**
	 * Parses {@code image/jpeg=jpg,image/png=png} into a lookup. Extensions are pinned in config
	 * rather than derived from the media type so a client cannot influence the stored object's
	 * suffix with something like {@code image/svg+xml}.
	 */
	private static Map<String, String> parseAllowlist(String allowedContentTypes) {
		Map<String, String> parsed = new LinkedHashMap<>();
		for (String entry : List.of(allowedContentTypes.split(","))) {
			String trimmed = entry.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			String[] parts = trimmed.split("=", 2);
			if (parts.length != 2) {
				throw new IllegalStateException(
						"app.media.allowed-content-types entries must be type=ext, got: " + trimmed);
			}
			parsed.put(parts[0].trim().toLowerCase(Locale.ROOT), parts[1].trim().toLowerCase(Locale.ROOT));
		}
		return Map.copyOf(parsed);
	}

	/** Exposed so the controller can report the limit without duplicating configuration. */
	public long maxBytes() {
		return maxBytes;
	}
}