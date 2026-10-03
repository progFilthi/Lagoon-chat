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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

	private static final String AUTHORIZATION_HEADER = "Authorization";
	private static final String BEARER_PREFIX = "Bearer ";
	private static final String ROLE_USER = "ROLE_USER";

	private static final Set<StompCommand> REQUIRES_AUTH = Set.of(StompCommand.SEND, StompCommand.SUBSCRIBE,
			StompCommand.UNSUBSCRIBE, StompCommand.ACK, StompCommand.NACK, StompCommand.BEGIN);

	private final JwtService jwtService;

	public StompAuthChannelInterceptor(JwtService jwtService) {
		this.jwtService = jwtService;
	}

	@Override
	public Message<?> preSend(Message<?> message, MessageChannel channel) {
		StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
		StompCommand command = accessor.getCommand();
		if (command == null) {
			return message;
		}
		if (command == StompCommand.CONNECT || command == StompCommand.STOMP) {
			authenticate(accessor);
		}
		else if (REQUIRES_AUTH.contains(command) && accessor.getUser() == null) {
			throw new AccessDeniedException("Not authenticated");
		}
		return message;
	}

	private void authenticate(StompHeaderAccessor accessor) {
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
		accessor.setUser(new UsernamePasswordAuthenticationToken(principal, null,
				List.of(new SimpleGrantedAuthority(ROLE_USER))));
	}

	private String resolveToken(StompHeaderAccessor accessor) {
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
}