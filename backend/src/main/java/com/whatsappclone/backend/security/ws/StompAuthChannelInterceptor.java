package com.whatsappclone.backend.security.ws;

import com.whatsappclone.backend.auth.AuthenticatedUser;
import com.whatsappclone.backend.auth.JwtService;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

	private static final String AUTHORIZATION_HEADER = "Authorization";
	private static final String BEARER_PREFIX = "Bearer ";
	private static final String ROLE_USER = "ROLE_USER";
	private static final String USER_TOPIC_PREFIX = "/topic/user.";

	private static final Set<StompCommand> REQUIRES_AUTH = Set.of(StompCommand.SEND, StompCommand.SUBSCRIBE,
			StompCommand.UNSUBSCRIBE, StompCommand.ACK, StompCommand.NACK, StompCommand.BEGIN);

	private final JwtService jwtService;
	private final StompSessionRegistry sessionRegistry;

	public StompAuthChannelInterceptor(JwtService jwtService, StompSessionRegistry sessionRegistry) {
		this.jwtService = jwtService;
		this.sessionRegistry = sessionRegistry;
	}

	@Override
	public Message<?> preSend(Message<?> message, MessageChannel channel) {
		StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
		StompCommand command = accessor.getCommand();
		if (command == null) {
			return message;
		}
		if (command == StompCommand.CONNECT || command == StompCommand.STOMP) {
			Authentication authentication = authenticate(accessor);
			StompPrincipal.store(accessor, authentication);
			sessionRegistry.register(accessor.getSessionId(), authentication);
		}
		else if (command == StompCommand.DISCONNECT) {
			sessionRegistry.remove(accessor.getSessionId());
		}
		else if (REQUIRES_AUTH.contains(command)) {
			Authentication authentication = StompPrincipal.resolve(accessor);
			if (authentication == null) {
				throw new AccessDeniedException("Not authenticated");
			}
			if (command == StompCommand.SUBSCRIBE) {
				authorizeSubscription(accessor, authentication);
			}
		}
		return message;
	}

	private void authorizeSubscription(StompHeaderAccessor accessor, Authentication authentication) {
		String destination = accessor.getDestination();
		if (destination == null || !destination.startsWith(USER_TOPIC_PREFIX)) {
			return;
		}
		int separator = destination.indexOf('.', USER_TOPIC_PREFIX.length());
		String requestedUserId = separator < 0 ? destination.substring(USER_TOPIC_PREFIX.length())
				: destination.substring(USER_TOPIC_PREFIX.length(), separator);
		if (!(authentication.getPrincipal() instanceof AuthenticatedUser user)
				|| !user.id().toString().equals(requestedUserId)) {
			throw new AccessDeniedException("Cannot subscribe to another user's queue");
		}
	}

	private Authentication authenticate(StompHeaderAccessor accessor) {
		String token = resolveToken(accessor);
		if (token == null) {
			throw new AccessDeniedException("Missing Authorization token");
		}
		AuthenticatedUser principal;
		try {
			principal = new AuthenticatedUser(jwtService.extractUserId(token), jwtService.extractUsername(token));
		}
		catch (RuntimeException e) {
			throw new AccessDeniedException("Invalid Authorization token", e);
		}
		return new UsernamePasswordAuthenticationToken(principal, null, List.of(new SimpleGrantedAuthority(ROLE_USER)));
	}

	/**
	 * Resolves the bearer token for a CONNECT frame from either source, in order.
	 *
	 * <p>The explicit STOMP {@code Authorization} header wins, because it is what a non-browser client
	 * sends and it keeps the token out of the transport entirely. The cookie is the fallback, and is
	 * the only thing a browser can offer: JavaScript builds the CONNECT frame and the WebSocket API
	 * cannot set request headers, so a browser holding its token in an httpOnly cookie has no way to
	 * put it in a STOMP header. {@link JwtCookieHandshakeInterceptor} captured it from the upgrade
	 * request, where the browser sends cookies on its own.
	 *
	 * <p>Preferring the header is also the safer order. A client that can set the header is not
	 * relying on ambient cookie state, so a cross-site request cannot borrow the session.
	 */
	private String resolveToken(StompHeaderAccessor accessor) {
		String header = bearerHeader(accessor);
		if (header != null) {
			return header;
		}
		return cookieToken(accessor);
	}

	private String bearerHeader(StompHeaderAccessor accessor) {
		List<String> headers = accessor.getNativeHeader(AUTHORIZATION_HEADER);
		if (headers != null && !headers.isEmpty()) {
			String header = headers.getFirst();
			if (header != null && header.startsWith(BEARER_PREFIX)) {
				String token = header.substring(BEARER_PREFIX.length()).trim();
				if (!token.isEmpty()) {
					return token;
				}
			}
		}
		return null;
	}

	private String cookieToken(StompHeaderAccessor accessor) {
		Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
		if (sessionAttributes == null) {
			return null;
		}
		Object captured = sessionAttributes.get(JwtCookieHandshakeInterceptor.ATTRIBUTE);
		if (captured instanceof String token && !token.isBlank()) {
			return token.trim();
		}
		return null;
	}
}