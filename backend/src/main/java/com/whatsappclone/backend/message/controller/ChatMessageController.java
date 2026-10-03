package com.whatsappclone.backend.message.controller;

import com.whatsappclone.backend.auth.AuthenticatedUser;
import com.whatsappclone.backend.common.exception.AppException;
import com.whatsappclone.backend.common.exception.ErrorCode;
import com.whatsappclone.backend.common.realtime.RealtimeBroadcaster;
import com.whatsappclone.backend.message.dto.ReceiptEventPayload;
import com.whatsappclone.backend.message.dto.SendMessagePayload;
import com.whatsappclone.backend.message.dto.SendMessageResponse;
import com.whatsappclone.backend.message.model.Message;
import com.whatsappclone.backend.message.model.MessageReceipt;
import com.whatsappclone.backend.message.model.MessageStatus;
import com.whatsappclone.backend.message.repository.MessageReceiptRepository;
import com.whatsappclone.backend.message.repository.MessageRepository;
import com.whatsappclone.backend.message.service.MessageService;
import com.whatsappclone.backend.security.ws.StompPrincipal;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.security.core.Authentication;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Controller
public class ChatMessageController {

	private static final Logger log = LoggerFactory.getLogger(ChatMessageController.class);

	private static final String SEND_CHANNEL = "messages";
	private static final String RECEIPT_CHANNEL = "receipts";
	private static final String READ_CHANNEL = "read";
	private static final String TYPING_CHANNEL = "typing";

	private final MessageService messageService;
	private final RealtimeBroadcaster broadcaster;
	private final MessageRepository messageRepository;
	private final MessageReceiptRepository messageReceiptRepository;

	public ChatMessageController(MessageService messageService, RealtimeBroadcaster broadcaster,
			MessageRepository messageRepository, MessageReceiptRepository messageReceiptRepository) {
		this.messageService = messageService;
		this.broadcaster = broadcaster;
		this.messageRepository = messageRepository;
		this.messageReceiptRepository = messageReceiptRepository;
	}

	@MessageMapping("/chat.send")
	public void send(StompHeaderAccessor accessor, @Valid @Payload SendMessagePayload payload) {
		UUID senderId = senderIdOf(StompPrincipal.resolve(accessor));
		SendMessageResponse saved = messageService.save(senderId, payload);
		List<UUID> recipients = messageService.recipientIdsForChat(payload.chatId(), senderId);
		recipients.forEach(recipientId -> broadcaster.sendToUser(recipientId, SEND_CHANNEL, saved));
		broadcaster.sendToUser(senderId, SEND_CHANNEL, saved);
	}

	@MessageMapping("/chat.receipt")
	public void receipt(StompHeaderAccessor accessor, @Payload ReceiptEventPayload payload) {
		UUID actorId = senderIdOf(StompPrincipal.resolve(accessor));
		if (payload.status() != MessageStatus.DELIVERED && payload.status() != MessageStatus.READ) {
			throw new AppException(ErrorCode.VALIDATION_ERROR, "status must be DELIVERED or READ");
		}
		boolean changed = messageService.updateReceipt(actorId, payload.messageId(), payload.status(),
				payload.occurredAt());
		if (!changed) {
			return;
		}
		Message message = messageRepository.findById(payload.messageId()).orElse(null);
		if (message == null) {
			return;
		}
		broadcaster.sendToUser(message.getSender().getId(), RECEIPT_CHANNEL, payload);
		broadcaster.sendToChat(message.getChat().getId(), READ_CHANNEL, payload);
	}

	@MessageMapping("/chat.typing")
	public void typing(StompHeaderAccessor accessor, @Payload ReceiptEventPayload payload) {
		UUID actorId = senderIdOf(StompPrincipal.resolve(accessor));
		UUID chatId = messageRepository.findById(payload.messageId())
				.map(message -> message.getChat().getId())
				.orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Message not found"));
		List<MessageReceipt> receipts = messageReceiptRepository.findByMessageId(payload.messageId());
		List<UUID> others = receipts.stream()
				.map(receipt -> receipt.getRecipient().getId())
				.filter(userId -> !userId.equals(actorId))
				.toList();
		others.forEach(userId -> broadcaster.sendToUser(userId, TYPING_CHANNEL,
				new TypingSignal(chatId, actorId, payload.occurredAt() != null ? payload.occurredAt()
						: Instant.now())));
	}

	@MessageExceptionHandler
	public void handleException(Throwable exception, SimpMessageHeaderAccessor accessor) {
		log.warn("Rejected STOMP message on destination {}: {}", accessor.getDestination(),
				exception.getMessage(), exception);
	}

	private UUID senderIdOf(Authentication principal) {
		if (principal != null && principal.getPrincipal() instanceof AuthenticatedUser user) {
			return user.id();
		}
		throw new AppException(ErrorCode.UNAUTHORIZED, "Not authenticated");
	}

	public record TypingSignal(UUID chatId, UUID userId, Instant at) {
	}
}