package com.whatsappclone.backend.message.controller;

import com.whatsappclone.backend.auth.AuthenticatedUser;
import com.whatsappclone.backend.common.exception.AppException;
import com.whatsappclone.backend.common.exception.ErrorCode;
import com.whatsappclone.backend.common.realtime.RealtimeBroadcaster;
import com.whatsappclone.backend.common.realtime.StompErrorPayload;
import com.whatsappclone.backend.message.dto.MessageResponse;
import com.whatsappclone.backend.message.dto.ReceiptEventPayload;
import com.whatsappclone.backend.message.dto.SendMessagePayload;
import com.whatsappclone.backend.message.model.Message;
import com.whatsappclone.backend.message.model.MessageReceipt;
import com.whatsappclone.backend.message.model.MessageStatus;
import com.whatsappclone.backend.message.repository.MessageReceiptRepository;
import com.whatsappclone.backend.message.repository.MessageRepository;
import com.whatsappclone.backend.message.service.MessageService;
import com.whatsappclone.backend.security.ws.StompPrincipal;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;
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
	private static final String ERROR_CHANNEL = "errors";

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
		MessageResponse saved = messageService.save(senderId, payload);
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

	/**
	 * Reports a rejected inbound frame to the sender's own error topic instead of swallowing it.
	 * Returns normally on every path: letting the exception escape would reach
	 * {@code StompSubProtocolHandler}, whose fallback sends an ERROR frame and closes the socket,
	 * turning one bad frame into a dropped connection. See {@link StompErrorPayload}.
	 */
	@MessageExceptionHandler
	public void handleException(Throwable exception, SimpMessageHeaderAccessor accessor) {
		String destination = accessor.getDestination();
		Rejection rejection = resolve(exception);
		UUID userId = userIdOrNull(StompPrincipal.resolve(accessor.getSessionAttributes()));
		log.warn("Rejected STOMP frame on {} for user {}: {}", destination, userId, rejection.code(), exception);
		if (userId == null) {
			return;
		}
		broadcaster.sendToUser(userId, ERROR_CHANNEL, new StompErrorPayload(rejection.code().name(),
				rejection.message(), destination, accessor.getFirstNativeHeader("receipt")));
	}

	/**
	 * Walks the cause chain rather than testing only the outermost exception. Spring reports a bad
	 * inbound frame as a {@code MethodArgumentNotValidException} from argument resolution, a bad
	 * payload as an {@code AppException} from the service, and an unparseable one as a converter
	 * failure several layers down, so the useful signal is rarely on the exception itself.
	 *
	 * <p>Messages are deliberately terse and match the wording {@code GlobalExceptionHandler} uses
	 * for the same codes. The raw exception text is not forwarded: for a validation failure it
	 * contains the entire rejected frame, so broadcasting it would echo the payload back and add
	 * nothing a client could not get from {@code code}.
	 */
	private Rejection resolve(Throwable exception) {
		for (Throwable current = exception; current != null; current = current.getCause()) {
			if (current instanceof AppException appException) {
				return new Rejection(appException.getErrorCode(), appException.getMessage());
			}
			if (current instanceof MethodArgumentNotValidException
					|| current instanceof ConstraintViolationException
					|| current instanceof IllegalArgumentException) {
				return new Rejection(ErrorCode.VALIDATION_ERROR, "Request validation failed");
			}
			if (current.getCause() == current) {
				break;
			}
		}
		return new Rejection(ErrorCode.INTERNAL_ERROR, "Unexpected server error");
	}

	private record Rejection(ErrorCode code, String message) {
	}

	private UUID userIdOrNull(Authentication principal) {
		if (principal != null && principal.getPrincipal() instanceof AuthenticatedUser user) {
			return user.id();
		}
		return null;
	}

	private UUID senderIdOf(Authentication principal) {
		UUID userId = userIdOrNull(principal);
		if (userId == null) {
			throw new AppException(ErrorCode.UNAUTHORIZED, "Not authenticated");
		}
		return userId;
	}

	public record TypingSignal(UUID chatId, UUID userId, Instant at) {
	}
}