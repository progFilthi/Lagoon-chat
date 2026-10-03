package com.whatsappclone.backend.auth.service;

import com.whatsappclone.backend.auth.JwtService;
import com.whatsappclone.backend.auth.dto.AuthResponse;
import com.whatsappclone.backend.auth.dto.LoginRequest;
import com.whatsappclone.backend.auth.dto.RegisterRequest;
import com.whatsappclone.backend.common.exception.AppException;
import com.whatsappclone.backend.common.exception.ErrorCode;
import com.whatsappclone.backend.user.model.User;
import com.whatsappclone.backend.user.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final JwtService jwtService;

	public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
		this.userRepository = userRepository;
		this.passwordEncoder = passwordEncoder;
		this.jwtService = jwtService;
	}

	@Transactional
	public AuthResponse register(RegisterRequest request) {
		if (userRepository.existsByPhoneNumber(request.phoneNumber())) {
			throw new AppException(ErrorCode.PHONE_ALREADY_REGISTERED, "That phone number is already registered");
		}
		if (userRepository.existsByUsername(request.username())) {
			throw new AppException(ErrorCode.USERNAME_ALREADY_TAKEN, "That username is already taken");
		}
		User user = User.register(request.phoneNumber(), request.username(),
				passwordEncoder.encode(request.password()));
		try {
			userRepository.saveAndFlush(user);
		}
		catch (DataIntegrityViolationException e) {
			throw new AppException(ErrorCode.CONFLICT, "Account could not be created");
		}
		return issueToken(user);
	}

	@Transactional(readOnly = true)
	public AuthResponse login(LoginRequest request) {
		User user = userRepository.findByPhoneNumber(request.phoneNumber())
				.filter(candidate -> candidate.isEnabled())
				.orElseThrow(() -> new AppException(ErrorCode.INVALID_CREDENTIALS, "Invalid phone number or password"));
		if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
			throw new AppException(ErrorCode.INVALID_CREDENTIALS, "Invalid phone number or password");
		}
		return issueToken(user);
	}

	private AuthResponse issueToken(User user) {
		String token = jwtService.generateAccessToken(user.getId(), user.getUsername());
		return AuthResponse.from(token, jwtService.expiresInSeconds(), user);
	}
}