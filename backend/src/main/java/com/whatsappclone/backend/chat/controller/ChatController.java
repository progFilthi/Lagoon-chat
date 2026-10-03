package com.whatsappclone.backend.chat.controller;

import com.whatsappclone.backend.chat.dto.ChatSummaryResponse;
import com.whatsappclone.backend.chat.dto.CreateChatRequest;
import com.whatsappclone.backend.chat.dto.CreateChatResponse;
import com.whatsappclone.backend.chat.service.ChatService;
import com.whatsappclone.backend.common.api.ApiResponse;
import com.whatsappclone.backend.common.api.PageResponse;
import com.whatsappclone.backend.message.dto.MessageResponse;
import com.whatsappclone.backend.message.service.MessageService;
import com.whatsappclone.backend.security.resolver.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/chats")
public class ChatController {

	private final ChatService chatService;
	private final MessageService messageService;

	public ChatController(ChatService chatService, MessageService messageService) {
		this.chatService = chatService;
		this.messageService = messageService;
	}

	@GetMapping
	public ApiResponse<List<ChatSummaryResponse>> list(@CurrentUser UUID currentUserId) {
		return ApiResponse.ok(chatService.listChats(currentUserId));
	}

	@PostMapping
	public ResponseEntity<ApiResponse<CreateChatResponse>> create(@CurrentUser UUID currentUserId,
			@Valid @RequestBody CreateChatRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(ApiResponse.ok(chatService.createChat(currentUserId, request)));
	}

	@GetMapping("/{chatId}")
	public ApiResponse<ChatSummaryResponse> detail(@CurrentUser UUID currentUserId, @PathVariable UUID chatId) {
		return ApiResponse.ok(chatService.listChats(currentUserId).stream()
				.filter(chat -> chat.id().equals(chatId))
				.findFirst()
				.orElseThrow(() -> new com.whatsappclone.backend.common.exception.AppException(
						com.whatsappclone.backend.common.exception.ErrorCode.NOT_FOUND,
						"Chat not found")));
	}

	@GetMapping("/{chatId}/messages")
	public ApiResponse<PageResponse<MessageResponse>> messages(@CurrentUser UUID currentUserId,
			@PathVariable UUID chatId,
			@RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer limit) {
		return ApiResponse.ok(messageService.history(currentUserId, chatId, cursor, limit));
	}

	@PostMapping("/{chatId}/read")
	public ApiResponse<Void> markRead(@CurrentUser UUID currentUserId, @PathVariable UUID chatId) {
		messageService.markChatRead(currentUserId, chatId, null);
		return ApiResponse.ok();
	}
}