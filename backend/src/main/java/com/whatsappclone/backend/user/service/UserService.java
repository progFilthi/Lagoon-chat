package com.whatsappclone.backend.user.service;

import com.whatsappclone.backend.common.crypto.HashUtils;
import com.whatsappclone.backend.common.exception.AppException;
import com.whatsappclone.backend.common.exception.ErrorCode;
import com.whatsappclone.backend.user.dto.UserProfileResponse;
import com.whatsappclone.backend.user.model.User;
import com.whatsappclone.backend.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class UserService {

	private final UserRepository userRepository;

	public UserService(UserRepository userRepository) {
		this.userRepository = userRepository;
	}

	/**
	 * Resolves local contact numbers to registered users. Hashing the numbers client-side is not an
	 * option here because the client sends them in the clear; the server hashes before querying so
	 * the lookup never runs against the plaintext column.
	 */
	@Transactional(readOnly = true)
	public List<UserProfileResponse> syncContacts(List<String> phoneNumbers) {
		Set<String> hashes = new LinkedHashSet<>();
		for (String phoneNumber : phoneNumbers) {
			hashes.add(HashUtils.sha256Hex(phoneNumber));
		}
		return userRepository.findAllByPhoneNumberHashIn(hashes).stream().map(UserProfileResponse::from).toList();
	}

	@Transactional(readOnly = true)
	public User requireUser(UUID userId) {
		return userRepository.findById(userId)
				.filter(User::isEnabled)
				.orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "User not found"));
	}

	@Transactional(readOnly = true)
	public User requireUserByPhoneNumber(String phoneNumber) {
		return userRepository.findByPhoneNumber(phoneNumber)
				.orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "User not found"));
	}
}