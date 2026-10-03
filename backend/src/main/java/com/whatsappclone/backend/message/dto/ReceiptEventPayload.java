package com.whatsappclone.backend.message.dto;

import com.whatsappclone.backend.message.model.MessageStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReceiptEventPayload(UUID messageId, MessageStatus status, List<UUID> chatMemberIds, Instant occurredAt) {
}