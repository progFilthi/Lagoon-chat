package com.whatsappclone.backend.presence;

import com.whatsappclone.backend.auth.AuthenticatedUser;
import com.whatsappclone.backend.chat.repository.ChatParticipantRepository;
import com.whatsappclone.backend.common.realtime.RealtimeBroadcaster;
import com.whatsappclone.backend.security.ws.StompSessionRegistry;
import com.whatsappclone.backend.user.model.User;
import com.whatsappclone.backend.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.Authentication;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.List;
import java.util.UUID;

@Component
public class PresenceEventListener {

	private static final Logger log = LoggerFactory.getLogger(PresenceEventListener.class);
	private static final String PRESENCE_CHANNEL = "presence";
	private static final String ONLINE_CHANNEL = "online-users";

	private final PresenceService presenceService;
	private final RealtimeBroadcaster presenceBroadcaster;
	private final StompSessionRegistry stompSessionRegistry;
	private final UserRepository userRepository;
	private final ChatParticipantRepository chatParticipantRepository;

	public PresenceEventListener(PresenceService presenceService, RealtimeBroadcaster presenceBroadcaster,
			StompSessionRegistry stompSessionRegistry, UserRepository userRepository,
			ChatParticipantRepository chatParticipantRepository) {
		this.presenceService = presenceService;
		this.stompSessionRegistry = stompSessionRegistry;
		this.presenceBroadcaster = presenceBroadcaster;
		this.userRepository = userRepository;
		this.chatParticipantRepository = chatParticipantRepository;
	}

	@EventListener
	@Transactional
	public void onConnect(SessionConnectedEvent event) {
		String sessionId = StompHeaderAccessor.wrap(event.getMessage()).getSessionId();
		AuthenticatedUser principal = principalOf(stompSessionRegistry.find(sessionId));
		if (principal == null) {
			return;
		}
		try {
			boolean online = presenceService.connect(principal.id(), sessionId);
			User user = markPresence(principal.id(), online);
			broadcast(principal.id(), user, online);
		}
		catch (RuntimeException e) {
			log.warn("Presence connect handling failed for {}: {}", principal.id(), e.getMessage());
		}
	}

	@EventListener
	@Transactional
	public void onDisconnect(SessionDisconnectEvent event) {
		AuthenticatedUser principal = principalOf(stompSessionRegistry.find(event.getSessionId()));
		if (principal == null) {
			return;
		}
		String sessionId = event.getSessionId();
		try {
			boolean stillOnline = presenceService.disconnect(principal.id(), sessionId);
			if (stillOnline) {
				return;
			}
			User user = markPresence(principal.id(), false);
			broadcast(principal.id(), user, false);
		}
		catch (RuntimeException e) {
			log.warn("Presence disconnect handling failed for {}: {}", principal.id(), e.getMessage());
		}
	}

	private User markPresence(UUID userId, boolean online) {
		User user = userRepository.findById(userId).orElse(null);
		if (user == null) {
			return null;
		}
		if (online) {
			user.markOnline();
		}
		else {
			user.markOffline();
		}
		return userRepository.save(user);
	}

	private void broadcast(UUID userId, User user, boolean online) {
		if (user == null) {
			return;
		}
		List<UUID> chatIds = chatParticipantRepository.findByUserId(userId).stream()
				.map(participant -> participant.getChat().getId())
				.distinct()
				.toList();
		PresenceEvent event = new PresenceEvent(userId, user.getUsername(), online, user.getLastSeen(), chatIds);
		presenceBroadcaster.sendToUser(userId, PRESENCE_CHANNEL, event);
		chatIds.forEach(chatId -> presenceBroadcaster.sendToChat(chatId, ONLINE_CHANNEL, event));
	}

	private AuthenticatedUser principalOf(Authentication authentication) {
		return authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser principal
				? principal : null;
	}
}