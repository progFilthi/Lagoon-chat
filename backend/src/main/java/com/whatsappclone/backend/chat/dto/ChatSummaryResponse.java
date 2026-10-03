package com.whatsappclone.backend.chat.dto;

import com.whatsappclone.backend.chat.model.ChatRole;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ChatSummaryResponse(UUID id, boolean group, String groupName, String groupAvatarUrl,
		Instant lastMessageAt, String lastMessagePreview, UUID lastMessageSenderId, long unreadCount,
		List<ParticipantResponse> participants) {

	public record ParticipantResponse(UUID userId, String username, String profilePictureUrl, ChatRole role,
			boolean online) {
	}
}