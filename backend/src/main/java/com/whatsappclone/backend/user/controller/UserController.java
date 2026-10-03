package com.whatsappclone.backend.user.controller;

import com.whatsappclone.backend.common.api.ApiResponse;
import com.whatsappclone.backend.security.resolver.CurrentUser;
import com.whatsappclone.backend.user.dto.SyncContactsRequest;
import com.whatsappclone.backend.user.dto.UserProfileResponse;
import com.whatsappclone.backend.user.model.User;
import com.whatsappclone.backend.user.service.UserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/users")
public class UserController {

	private final UserService userService;

	public UserController(UserService userService) {
		this.userService = userService;
	}

	@PostMapping("/sync")
	public ApiResponse<List<UserProfileResponse>> sync(@Valid @RequestBody SyncContactsRequest request) {
		return ApiResponse.ok(userService.syncContacts(request.phoneNumbers()));
	}

	@GetMapping("/me")
	public ApiResponse<UserProfileResponse> me(@CurrentUser UUID currentUserId) {
		User user = userService.requireUser(currentUserId);
		return ApiResponse.ok(UserProfileResponse.from(user));
	}

	@GetMapping("/{userId}")
	public ApiResponse<UserProfileResponse> profile(@PathVariable UUID userId) {
		return ApiResponse.ok(UserProfileResponse.from(userService.requireUser(userId)));
	}
}