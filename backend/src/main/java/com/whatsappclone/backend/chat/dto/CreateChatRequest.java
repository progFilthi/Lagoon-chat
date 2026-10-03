package com.whatsappclone.backend.chat.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record CreateChatRequest(

		@Size(max = 120, message = "groupName must be at most 120 characters")
		String groupName,

		@NotEmpty(message = "memberIds must not be empty")
		@Size(max = 255, message = "a group can have at most 255 members")
		List<UUID> memberIds) {

	public boolean isGroup() {
		return groupName != null && !groupName.isBlank();
	}
}