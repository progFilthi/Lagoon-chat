package com.whatsappclone.backend.chat.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CreateChatResponse(UUID chatId, boolean group, String groupName, Instant createdAt,
		List<UUID> memberIds) {
}