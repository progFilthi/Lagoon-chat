package com.whatsappclone.backend.message.dto;

import com.whatsappclone.backend.chat.model.ChatRole;
import com.whatsappclone.backend.message.model.MessageType;

import java.time.Instant;
import java.util.UUID;

public record SendMessageResponse(UUID messageId, UUID chatId, String clientMessageId, MessageType type,
		Instant createdAt, ChatRole senderRole) {
}