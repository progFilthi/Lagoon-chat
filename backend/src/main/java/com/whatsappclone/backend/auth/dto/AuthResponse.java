package com.whatsappclone.backend.auth.dto;

import com.whatsappclone.backend.user.model.User;

import java.util.UUID;

public record AuthResponse(String accessToken, String tokenType, long expiresIn, AuthenticatedUserResponse user) {

	public static AuthResponse from(String accessToken, long expiresIn, User user) {
		return new AuthResponse(accessToken, "Bearer", expiresIn,
				new AuthenticatedUserResponse(user.getId(), user.getUsername(), user.getPhoneNumber(),
						user.getProfilePictureUrl(), user.getAbout()));
	}

	public record AuthenticatedUserResponse(UUID id, String username, String phoneNumber, String profilePictureUrl,
			String about) {
	}
}