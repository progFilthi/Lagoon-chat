package com.whatsappclone.backend.message.dto;

import com.whatsappclone.backend.message.model.Message;
import com.whatsappclone.backend.message.model.MessageStatus;
import com.whatsappclone.backend.message.model.MessageType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MessageResponse(UUID id, UUID chatId, UUID senderId, String clientMessageId, String content,
		MessageType type, UUID replyToId, Instant createdAt, ReceiptSummary receipts) {

	public record ReceiptSummary(int total, int delivered, int read) {
	}

	public static MessageResponse from(Message message, String decryptedContent, List<UUID> recipientIds,
			List<UUID> deliveredIds, List<UUID> readIds) {
		ReceiptSummary summary = new ReceiptSummary(recipientIds.size(), deliveredIds.size(), readIds.size());
		return new MessageResponse(message.getId(), message.getChat().getId(), message.getSender().getId(),
				message.getClientMessageId(), decryptedContent, message.getType(),
				message.getReplyTo() != null ? message.getReplyTo().getId() : null, message.getCreatedAt(), summary);
	}

	public static MessageStatus aggregateStatus(ReceiptSummary summary) {
		if (summary.total() == summary.read()) {
			return MessageStatus.READ;
		}
		if (summary.total() == summary.delivered()) {
			return MessageStatus.DELIVERED;
		}
		return MessageStatus.SENT;
	}
}