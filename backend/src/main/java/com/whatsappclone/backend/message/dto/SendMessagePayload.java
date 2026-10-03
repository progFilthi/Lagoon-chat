package com.whatsappclone.backend.message.dto;

import com.whatsappclone.backend.message.model.MessageType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record SendMessagePayload(UUID chatId,

		@NotBlank(message = "clientMessageId is required")
		@Size(max = 64, message = "clientMessageId must be at most 64 characters")
		String clientMessageId,

		@NotBlank(message = "content is required")
		@Size(max = 8000, message = "content must be at most 8000 characters")
		String content,

		@NotNull(message = "type is required")
		@Pattern(regexp = "TEXT|IMAGE|VIDEO|AUDIO", message = "type must be TEXT, IMAGE, VIDEO or AUDIO")
		String type,

		UUID replyToId) {

	public MessageType messageType() {
		return MessageType.valueOf(type);
	}
}