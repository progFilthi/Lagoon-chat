package com.whatsappclone.backend.message.dto;

import com.whatsappclone.backend.message.model.MessageType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * @param mediaKey S3 object key previously obtained from {@code POST /api/media/upload-url}. Required
 * for {@code IMAGE}, {@code VIDEO} and {@code AUDIO}, and rejected for {@code TEXT}. The service
 * checks that the key lives under the sender's own prefix, so a key belonging to someone else
 * cannot be attached to your message and then read by your chat's members.
 */
public record SendMessagePayload(UUID chatId,

		@NotBlank(message = "clientMessageId is required")
		@Size(max = 64, message = "clientMessageId must be at most 64 characters")
		String clientMessageId,

		@Size(max = 8000, message = "content must be at most 8000 characters")
		String content,

		@NotNull(message = "type is required")
		@Pattern(regexp = "TEXT|IMAGE|VIDEO|AUDIO", message = "type must be TEXT, IMAGE, VIDEO or AUDIO")
		String type,

		UUID replyToId,

		@Size(max = 512, message = "mediaKey must be at most 512 characters")
		String mediaKey) {

	public MessageType messageType() {
		return MessageType.valueOf(type);
	}
}