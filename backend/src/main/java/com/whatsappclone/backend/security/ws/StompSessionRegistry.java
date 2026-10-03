package com.whatsappclone.backend.security.ws;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridges the authenticated user to the STOMP session lifecycle.
 *
 * {@code SessionConnectedEvent} exposes the session id but its accessor carries no session
 * attributes, so the principal established during the CONNECT frame cannot be read back from it.
 * The interceptor registers the authentication here instead, keyed by session id, and the
 * presence listener resolves it on connect.
 */
@Component
public class StompSessionRegistry {

	private final Map<String, Authentication> sessions = new ConcurrentHashMap<>();

	public void register(String sessionId, Authentication authentication) {
		sessions.put(sessionId, authentication);
	}

	public Authentication find(String sessionId) {
		return sessionId != null ? sessions.get(sessionId) : null;
	}

	public Authentication remove(String sessionId) {
		return sessionId != null ? sessions.remove(sessionId) : null;
	}
}