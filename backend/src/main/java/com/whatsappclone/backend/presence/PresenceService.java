package com.whatsappclone.backend.presence;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class PresenceService {

	private static final Duration SESSION_TTL = Duration.ofHours(24);
	private static final String SESSION_KEY_PREFIX = "presence-session:";

	private final StringRedisTemplate redisTemplate;
	private final String keyPrefix;

	public PresenceService(StringRedisTemplate redisTemplate, @Value("${app.presence.key-prefix}") String keyPrefix) {
		this.redisTemplate = redisTemplate;
		this.keyPrefix = keyPrefix;
	}

	public boolean connect(UUID userId, String sessionId) {
		String key = key(userId);
		redisTemplate.opsForSet().add(key, sessionId);
		redisTemplate.expire(key, SESSION_TTL);
		redisTemplate.opsForValue().set(sessionKey(sessionId), userId.toString(), SESSION_TTL);
		return isOnline(userId);
	}

	public boolean disconnect(UUID userId, String sessionId) {
		String key = key(userId);
		redisTemplate.opsForSet().remove(key, sessionId);
		redisTemplate.delete(sessionKey(sessionId));
		Long remaining = redisTemplate.opsForSet().size(key);
		if (remaining != null && remaining == 0L) {
			redisTemplate.delete(key);
			return false;
		}
		return remaining != null && remaining > 0;
	}

	public boolean isOnline(UUID userId) {
		try {
			Long size = redisTemplate.opsForSet().size(key(userId));
			return size != null && size > 0;
		}
		catch (RuntimeException e) {
			return false;
		}
	}

	public Set<String> activeSessions(UUID userId) {
		try {
			Set<String> members = redisTemplate.opsForSet().members(key(userId));
			return members == null ? Collections.emptySet() : members;
		}
		catch (RuntimeException e) {
			return Collections.emptySet();
		}
	}

	public Optional<UUID> userIdForSession(String sessionId) {
		try {
			String userId = redisTemplate.opsForValue().get(sessionKey(sessionId));
			return userId == null ? Optional.empty() : Optional.of(UUID.fromString(userId));
		}
		catch (RuntimeException e) {
			return Optional.empty();
		}
	}

	private Optional<UUID> parseUserId(String key) {
		String raw = key.substring(keyPrefix.length());
		try {
			return Optional.of(UUID.fromString(raw));
		}
		catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}

	private String key(UUID userId) {
		return keyPrefix + userId;
	}

	private String sessionKey(String sessionId) {
		return SESSION_KEY_PREFIX + sessionId;
	}
}