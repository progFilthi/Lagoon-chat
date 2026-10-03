package com.whatsappclone.backend.security.ws;

import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.core.Authentication;

import java.util.Map;

public final class StompPrincipal {

	public static final String SESSION_ATTRIBUTE = "whatsapp.principal";

	private StompPrincipal() {
	}

	public static void store(StompHeaderAccessor accessor, Authentication authentication) {
		accessor.setUser(authentication);
		Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
		if (sessionAttributes != null) {
			sessionAttributes.put(SESSION_ATTRIBUTE, authentication);
		}
	}

	/**
	 * Inbound STOMP frames carry immutable headers, so the principal cannot be written back into
	 * the {@code simpUser} header for handlers to read. The WebSocket session attributes map is
	 * mutable and survives across every frame on the connection, so the authentication
	 * established at CONNECT is resolved from there instead.
	 */
	public static Authentication resolve(StompHeaderAccessor accessor) {
		return resolve(accessor.getSessionAttributes());
	}

	public static Authentication resolve(Map<String, Object> sessionAttributes) {
		Object stored = sessionAttributes != null ? sessionAttributes.get(SESSION_ATTRIBUTE) : null;
		if (stored instanceof Authentication authentication) {
			return authentication;
		}
		return null;
	}
}