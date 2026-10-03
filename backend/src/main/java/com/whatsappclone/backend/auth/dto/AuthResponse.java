package com.whatsappclone.backend.auth.dto;

import com.whatsappclone.backend.user.dto.UserProfileResponse;
import com.whatsappclone.backend.user.model.User;

import java.util.UUID;

/**
 * The one user shape the API returns. Login, registration, profile lookups and contact sync all
 * carry {@link UserProfileResponse}, so a client has a single type to model a user rather than one
 * per endpoint. At login the presence fields are simply empty — {@code online} is false and
 * {@code lastSeen} is null until the client opens a socket.
 */
public record AuthResponse(String accessToken, String tokenType, long expiresIn, UserProfileResponse user) {

	public static AuthResponse from(String accessToken, long expiresIn, User user) {
		return new AuthResponse(accessToken, "Bearer", expiresIn, UserProfileResponse.from(user));
	}
}