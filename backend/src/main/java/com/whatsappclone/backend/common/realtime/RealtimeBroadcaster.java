package com.whatsappclone.backend.common.realtime;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Realtime fan-out uses explicit broker destinations rather than Spring's user destinations.
 *
 * {@code convertAndSendToUser} resolves the target sessions through {@code SimpUserRegistry},
 * which is keyed on the {@code Principal} attached to the WebSocketSession. STOMP CONNECT frames
 * arrive with immutable headers, so that Principal never gets registered and every
 * {@code convertAndSendToUser} call degrades into a silent no-op. Explicit destinations need no
 * Principal and, because the inbound channel authorises SUBSCRIBE, they can be locked down per
 * user. See {@code StompAuthChannelInterceptor}.
 *
 * Destinations are dot separated rather than slash separated because RabbitMQ's topic exchange
 * validates each word of the routing key and rejects any containing {@code /}.
 */
@Service
public class RealtimeBroadcaster {

	private static final String USER_PREFIX = "/topic/user.";
	private static final String CHAT_PREFIX = "/topic/chat.";

	private final SimpMessagingTemplate messagingTemplate;

	public RealtimeBroadcaster(SimpMessagingTemplate messagingTemplate) {
		this.messagingTemplate = messagingTemplate;
	}

	public void sendToUser(UUID userId, String channel, Object payload) {
		messagingTemplate.convertAndSend(USER_PREFIX + userId + "." + channel, payload);
	}

	public void sendToChat(UUID chatId, String channel, Object payload) {
		messagingTemplate.convertAndSend(CHAT_PREFIX + chatId + "." + channel, payload);
	}
}