package com.whatsappclone.backend.message.service;

import com.whatsappclone.backend.chat.model.Chat;
import com.whatsappclone.backend.chat.model.ChatParticipant;
import com.whatsappclone.backend.chat.repository.ChatParticipantRepository;
import com.whatsappclone.backend.chat.repository.ChatRepository;
import com.whatsappclone.backend.common.api.PageResponse;
import com.whatsappclone.backend.common.crypto.ContentEncryptionService;
import com.whatsappclone.backend.common.exception.AppException;
import com.whatsappclone.backend.common.exception.ErrorCode;
import com.whatsappclone.backend.message.dto.MessageCursor;
import com.whatsappclone.backend.message.dto.MessageResponse;
import com.whatsappclone.backend.message.dto.MessageResponse.ReceiptSummary;
import com.whatsappclone.backend.message.dto.SendMessagePayload;
import com.whatsappclone.backend.message.model.Message;
import com.whatsappclone.backend.message.model.MessageReceipt;
import com.whatsappclone.backend.message.model.MessageStatus;
import com.whatsappclone.backend.message.model.MessageType;
import com.whatsappclone.backend.message.repository.MessageReceiptRepository;
import com.whatsappclone.backend.message.repository.MessageRepository;
import com.whatsappclone.backend.user.repository.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class MessageService {

	private static final int MAX_PAGE_SIZE = 100;
	private static final int DEFAULT_PAGE_SIZE = 30;

	private final MessageRepository messageRepository;
	private final MessageReceiptRepository messageReceiptRepository;
	private final ChatRepository chatRepository;
	private final ChatParticipantRepository chatParticipantRepository;
	private final UserRepository userRepository;
	private final ContentEncryptionService contentEncryptionService;

	public MessageService(MessageRepository messageRepository, MessageReceiptRepository messageReceiptRepository,
			ChatRepository chatRepository, ChatParticipantRepository chatParticipantRepository,
			UserRepository userRepository, ContentEncryptionService contentEncryptionService) {
		this.messageRepository = messageRepository;
		this.messageReceiptRepository = messageReceiptRepository;
		this.chatRepository = chatRepository;
		this.chatParticipantRepository = chatParticipantRepository;
		this.userRepository = userRepository;
		this.contentEncryptionService = contentEncryptionService;
	}

	@Transactional
	public MessageResponse save(UUID senderId, SendMessagePayload payload) {
		Optional<Message> duplicate = messageRepository.findBySenderIdAndClientMessageId(senderId,
				payload.clientMessageId());
		if (duplicate.isPresent()) {
			return project(senderId, duplicate.get(),
					messageReceiptRepository.findByMessageId(duplicate.get().getId()));
		}

		ChatParticipant senderMembership = chatParticipantRepository
				.findByChatIdAndUserId(payload.chatId(), senderId)
				.orElseThrow(() -> new AppException(ErrorCode.NOT_CHAT_PARTICIPANT,
						"You are not a member of this chat"));
		Chat chat = senderMembership.getChat();

		MessageType messageType = payload.messageType();
		String content = payload.content() != null ? payload.content() : "";
		String mediaKey = resolveMediaKey(payload, senderId, messageType, content);

		Message replyTo = null;
		if (payload.replyToId() != null) {
			replyTo = messageRepository.findById(payload.replyToId())
					.filter(candidate -> candidate.getChat().getId().equals(payload.chatId()))
					.orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Replied-to message not found"));
		}

		Message created = Message.create(chat, senderMembership.getUser(), payload.clientMessageId(),
				contentEncryptionService.encrypt(content), messageType, replyTo, mediaKey);
		Message message = messageRepository.saveAndFlush(created);

		List<ChatParticipant> members = chatParticipantRepository.findByChatId(payload.chatId());
		List<MessageReceipt> receipts = members.stream()
				.filter(participant -> !participant.getUser().getId().equals(senderId))
				.map(participant -> MessageReceipt.pending(message, participant.getUser()))
				.toList();
		messageReceiptRepository.saveAll(receipts);

		chat.touch(message.getCreatedAt());
		chatRepository.save(chat);

		return project(senderId, message, receipts);
	}

	@Transactional(readOnly = true)
	public PageResponse<MessageResponse> history(UUID requesterId, UUID chatId, String cursor, Integer limit) {
		chatParticipantRepository.findByChatIdAndUserId(chatId, requesterId)
				.orElseThrow(() -> new AppException(ErrorCode.NOT_CHAT_PARTICIPANT,
						"You are not a member of this chat"));

		int pageSize = limit == null ? DEFAULT_PAGE_SIZE : Math.clamp(limit, 1, MAX_PAGE_SIZE);
		MessageCursor decoded = (cursor == null || cursor.isBlank()) ? null : MessageCursor.decode(cursor);

		List<Message> messages = decoded == null
				? messageRepository.findFirstPage(chatId, PageRequest.of(0, pageSize + 1))
				: messageRepository.findPageBefore(chatId, decoded.createdAt(), decoded.id(),
						PageRequest.of(0, pageSize + 1));

		boolean hasMore = messages.size() > pageSize;
		List<Message> page = hasMore ? messages.subList(0, pageSize) : messages;
		List<MessageResponse> content = toResponses(requesterId, page);
		String nextCursor = hasMore ? new MessageCursor(page.get(page.size() - 1).getCreatedAt(),
				page.get(page.size() - 1).getId()).encode() : null;
		return PageResponse.of(content, pageSize, hasMore, nextCursor);
	}

	@Transactional
	public boolean updateReceipt(UUID requesterId, UUID messageId, MessageStatus status, Instant occurredAt) {
		Message message = messageRepository.findById(messageId)
				.orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Message not found"));
		if (message.getSender().getId().equals(requesterId)) {
			return false;
		}
		chatParticipantRepository.findByChatIdAndUserId(message.getChat().getId(), requesterId)
				.orElseThrow(() -> new AppException(ErrorCode.NOT_CHAT_PARTICIPANT,
						"You are not a member of this chat"));
		Instant now = occurredAt != null ? occurredAt : Instant.now();
		return messageReceiptRepository.markStatus(messageId, requesterId, status, MessageStatus.SENT,
				MessageStatus.DELIVERED, MessageStatus.READ, now) > 0;
	}

	/**
	 * Marks everything currently in the chat read for this user and returns the instant applied, so
	 * the caller can broadcast the same value it persisted rather than re-deriving it.
	 */
	@Transactional
	public Instant markChatRead(UUID requesterId, UUID chatId, Instant readAt) {
		ChatParticipant participant = chatParticipantRepository.findByChatIdAndUserId(chatId, requesterId)
				.orElseThrow(() -> new AppException(ErrorCode.NOT_CHAT_PARTICIPANT,
						"You are not a member of this chat"));
		Instant effective = readAt != null ? readAt : Instant.now();
		participant.markReadAt(effective);
		chatParticipantRepository.save(participant);

		List<Message> recent = messageRepository.findLatestInChat(chatId, PageRequest.of(0, MAX_PAGE_SIZE));
		if (recent.isEmpty()) {
			return effective;
		}
		Set<UUID> ids = recent.stream().map(Message::getId).collect(Collectors.toSet());
		List<MessageReceipt> receipts = messageReceiptRepository.findForUserAndMessages(requesterId, ids);
		for (MessageReceipt receipt : receipts) {
			if (receipt.getStatus() != MessageStatus.READ) {
				receipt.markRead(effective);
			}
		}
		messageReceiptRepository.saveAll(receipts);
		return effective;
	}

	@Transactional(readOnly = true)
	public List<UUID> recipientIdsForChat(UUID chatId, UUID senderId) {
		return chatParticipantRepository.findOtherMemberIds(chatId, senderId);
	}

	@Transactional(readOnly = true)
	public List<UUID> chatMemberIds(UUID chatId) {
		return chatParticipantRepository.findByChatId(chatId).stream().map(p -> p.getUser().getId()).toList();
	}

	/**
	 * Enforces the two invariants that annotations cannot express: a text message must carry a body,
	 * and an attachment must reference an object the sender actually uploaded.
	 *
	 * <p>The key prefix check is the authorisation boundary for media. Keys are issued by
	 * {@code MediaService} under {@code media/{userId}/}, so requiring that prefix means a sender
	 * cannot attach someone else's object — otherwise they could post it to their own chat and have
	 * the whole chat download a file they never had access to.
	 */
	private String resolveMediaKey(SendMessagePayload payload, UUID senderId, MessageType type, String content) {
		boolean wantsMedia = type != MessageType.TEXT;
		String mediaKey = payload.mediaKey();

		if (!wantsMedia) {
			if (mediaKey != null && !mediaKey.isBlank()) {
				throw new AppException(ErrorCode.VALIDATION_ERROR, "mediaKey is only valid on media messages");
			}
			if (content.isBlank()) {
				throw new AppException(ErrorCode.VALIDATION_ERROR, "content is required on a TEXT message");
			}
			return null;
		}

		if (mediaKey == null || mediaKey.isBlank()) {
			throw new AppException(ErrorCode.VALIDATION_ERROR, "mediaKey is required on a " + type + " message");
		}
		String expectedPrefix = "media/" + senderId + "/";
		if (!mediaKey.startsWith(expectedPrefix)) {
			throw new AppException(ErrorCode.VALIDATION_ERROR, "mediaKey was not issued to you");
		}
		return mediaKey;
	}

	private List<MessageResponse> toResponses(UUID viewerId, List<Message> messages) {
		if (messages.isEmpty()) {
			return List.of();
		}
		List<UUID> messageIds = messages.stream().map(Message::getId).toList();
		Map<UUID, List<MessageReceipt>> byMessage = messageReceiptRepository.findByMessageIdIn(messageIds).stream()
				.collect(Collectors.groupingBy(receipt -> receipt.getMessage().getId()));

		List<MessageResponse> responses = new ArrayList<>(messages.size());
		for (Message message : messages) {
			responses.add(project(viewerId, message, byMessage.getOrDefault(message.getId(), List.of())));
		}
		return responses;
	}

	/**
	 * The one projection of a message used by every surface: history, and the broadcast sent on
	 * {@code chat.send}. There is deliberately no second shape for the socket, because a client
	 * holding two shapes for the same message has to reconcile them on every send — and the socket
	 * version is the one carrying no {@code content}, so a recipient cannot render the message
	 * without refetching history.
	 *
	 * <p>Receipt counts are only populated for the viewer's own messages. On anyone else's you
	 * already know the state, and populating it would tell the whole group who has read what.
	 */
	private MessageResponse project(UUID viewerId, Message message, List<MessageReceipt> receipts) {
		boolean senderView = message.getSender().getId().equals(viewerId);
		ReceiptSummary summary = senderView
				? new ReceiptSummary(receipts.size(),
						(int) receipts.stream().filter(r -> r.getStatus().isAtLeast(MessageStatus.DELIVERED)).count(),
						(int) receipts.stream().filter(r -> r.getStatus() == MessageStatus.READ).count())
				: new ReceiptSummary(0, 0, 0);
		return MessageResponse.from(message, contentEncryptionService.decrypt(message.getContent()), summary);
	}
}