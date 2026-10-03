package com.whatsappclone.backend.message.dto;

import com.whatsappclone.backend.message.model.Message;
import com.whatsappclone.backend.message.model.MessageType;

import java.time.Instant;
import java.util.UUID;

public record MessageResponse(UUID id, UUID chatId, UUID senderId, String clientMessageId, String content,
		MessageType type, UUID replyToId, Instant createdAt, ReceiptSummary receipts) {

	/**
	 * {@code delivered} counts every receipt at DELIVERED <em>or beyond</em>, so it includes the
	 * ones already read. A message is only fully delivered when {@code delivered == total}, and
	 * {@code read == total} means everyone has read it.
	 */
	public record ReceiptSummary(int total, int delivered, int read) {
	}

	public static MessageResponse from(Message message, String decryptedContent, ReceiptSummary receipts) {
		return new MessageResponse(message.getId(), message.getChat().getId(), message.getSender().getId(),
				message.getClientMessageId(), decryptedContent, message.getType(),
				message.getReplyTo() != null ? message.getReplyTo().getId() : null, message.getCreatedAt(), receipts);
	}
}