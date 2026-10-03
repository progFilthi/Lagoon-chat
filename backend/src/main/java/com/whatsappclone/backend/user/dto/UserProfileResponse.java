package com.whatsappclone.backend.user.dto;

import com.whatsappclone.backend.user.model.User;

import java.time.Instant;
import java.util.UUID;

public record UserProfileResponse(UUID id, String username, String phoneNumber, String profilePictureUrl, String about,
		boolean online, Instant lastSeen) {

	public static UserProfileResponse from(User user) {
		return new UserProfileResponse(user.getId(), user.getUsername(), user.getPhoneNumber(),
				user.getProfilePictureUrl(), user.getAbout(), user.isOnline(), user.getLastSeen());
	}
}