package com.whatsappclone.backend.user.dto;

import com.whatsappclone.backend.user.model.User;

import java.time.Instant;
import java.util.UUID;

public record SyncedContact(UUID id, String username, String phoneNumber, String profilePictureUrl, String about,
		boolean online, Instant lastSeen) {

	public static SyncedContact from(User user) {
		return new SyncedContact(user.getId(), user.getUsername(), user.getPhoneNumber(),
				user.getProfilePictureUrl(), user.getAbout(), user.isOnline(), user.getLastSeen());
	}
}