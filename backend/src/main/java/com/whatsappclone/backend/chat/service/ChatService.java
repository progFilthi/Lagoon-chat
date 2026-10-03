package com.whatsappclone.backend.chat.service;

import com.whatsappclone.backend.chat.dto.ChatSummaryResponse;
import com.whatsappclone.backend.chat.dto.CreateChatRequest;
import com.whatsappclone.backend.chat.dto.CreateChatResponse;
import com.whatsappclone.backend.chat.model.Chat;
import com.whatsappclone.backend.chat.model.ChatParticipant;
import com.whatsappclone.backend.chat.model.ChatRole;
import com.whatsappclone.backend.chat.repository.ChatParticipantRepository;
import com.whatsappclone.backend.chat.repository.ChatRepository;
import com.whatsappclone.backend.chat.repository.projection.UnreadCount;
import com.whatsappclone.backend.common.crypto.ContentEncryptionService;
import com.whatsappclone.backend.common.exception.AppException;
import com.whatsappclone.backend.common.exception.ErrorCode;
import com.whatsappclone.backend.message.model.Message;
import com.whatsappclone.backend.message.model.MessageStatus;
import com.whatsappclone.backend.message.repository.MessageRepository;
import com.whatsappclone.backend.user.model.User;
import com.whatsappclone.backend.user.repository.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ChatService {

	private static final int MAX_CHATS_PER_REQUEST = 100;

	private final ChatRepository chatRepository;
	private final ChatParticipantRepository chatParticipantRepository;
	private final MessageRepository messageRepository;
	private final UserRepository userRepository;
	private final ContentEncryptionService contentEncryptionService;

	public ChatService(ChatRepository chatRepository, ChatParticipantRepository chatParticipantRepository,
			MessageRepository messageRepository, UserRepository userRepository,
			ContentEncryptionService contentEncryptionService) {
		this.chatRepository = chatRepository;
		this.chatParticipantRepository = chatParticipantRepository;
		this.messageRepository = messageRepository;
		this.userRepository = userRepository;
		this.contentEncryptionService = contentEncryptionService;
	}

	@Transactional(readOnly = true)
	public List<ChatSummaryResponse> listChats(UUID userId) {
		List<Chat> chats = chatRepository.findAllForUser(userId,
				PageRequest.of(0, MAX_CHATS_PER_REQUEST, Sort.by(Sort.Order.desc("lastMessageAt"),
						Sort.Order.asc("id"))));
		if (chats.isEmpty()) {
			return List.of();
		}
		List<UUID> chatIds = chats.stream().map(Chat::getId).toList();

		Map<UUID, Long> unread = chatParticipantRepository.countUnreadByChat(userId, MessageStatus.READ).stream()
				.collect(Collectors.toMap(UnreadCount::getChatId, UnreadCount::getUnreadCount));
		Map<UUID, List<ChatParticipant>> membersByChat = chatParticipantRepository.findByChatIdIn(chatIds).stream()
				.collect(Collectors.groupingBy(participant -> participant.getChat().getId()));

		List<ChatSummaryResponse> summaries = new ArrayList<>(chats.size());
		for (Chat chat : chats) {
			Optional<Message> latest = messageRepository.findLatestInChat(chat.getId(), PageRequest.of(0, 1))
					.stream().findFirst();
			List<ChatSummaryResponse.ParticipantResponse> participants = membersByChat
					.getOrDefault(chat.getId(), List.of()).stream()
					.map(participant -> new ChatSummaryResponse.ParticipantResponse(participant.getUser().getId(),
							participant.getUser().getUsername(), participant.getUser().getProfilePictureUrl(),
							participant.getRole(), participant.getUser().isOnline()))
					.toList();
			summaries.add(new ChatSummaryResponse(chat.getId(), chat.isGroup(), chat.getGroupName(),
					chat.getGroupAvatarUrl(), chat.getLastMessageAt(),
					latest.map(this::previewOf).orElse(null),
					latest.map(message -> message.getSender().getId()).orElse(null),
					unread.getOrDefault(chat.getId(), 0L), participants));
		}
		return summaries;
	}

	@Transactional
	public CreateChatResponse createChat(UUID creatorId, CreateChatRequest request) {
		Set<UUID> requestedMembers = new LinkedHashSet<>(request.memberIds());
		requestedMembers.remove(creatorId);

		List<User> others = userRepository.findByIdIn(requestedMembers);
		if (others.size() != requestedMembers.size()) {
			throw new AppException(ErrorCode.NOT_FOUND, "One or more members do not exist");
		}

		if (!request.isGroup()) {
			if (requestedMembers.size() != 1) {
				throw new AppException(ErrorCode.VALIDATION_ERROR,
						"A direct chat must have exactly one other member; supply groupName to create a group");
			}
			Chat existing = chatRepository
					.findDirectChatBetween(creatorId, requestedMembers.iterator().next())
					.orElse(null);
			if (existing != null) {
				return new CreateChatResponse(existing.getId(), false, null, existing.getCreatedAt(),
						membersOf(existing.getId()));
			}
		}

		Chat created = request.isGroup() ? Chat.group(request.groupName().trim(), creatorId)
				: Chat.direct(creatorId);
		Chat chat = chatRepository.saveAndFlush(created);

		List<ChatParticipant> participants = new ArrayList<>();
		participants.add(ChatParticipant.join(chat, requireUser(creatorId), ChatRole.ADMIN));
		for (User member : others) {
			participants.add(ChatParticipant.join(chat, member, ChatRole.MEMBER));
		}
		chatParticipantRepository.saveAll(participants);

		return new CreateChatResponse(chat.getId(), chat.isGroup(), chat.getGroupName(), chat.getCreatedAt(),
				participants.stream().map(participant -> participant.getUser().getId()).toList());
	}

	@Transactional(readOnly = true)
	public void requireMembership(UUID chatId, UUID userId) {
		chatParticipantRepository.findByChatIdAndUserId(chatId, userId)
				.orElseThrow(() -> new AppException(ErrorCode.NOT_CHAT_PARTICIPANT,
						"You are not a member of this chat"));
	}

	private List<UUID> membersOf(UUID chatId) {
		return chatParticipantRepository.findByChatId(chatId).stream().map(p -> p.getUser().getId()).toList();
	}

	private User requireUser(UUID userId) {
		return userRepository.findById(userId)
				.orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "User not found"));
	}

	private String previewOf(Message message) {
		String content = contentEncryptionService.decrypt(message.getContent());
		int newline = content.indexOf('\n');
		return newline >= 0 && newline < 40 ? content.substring(0, newline) + "..." : content;
	}
}