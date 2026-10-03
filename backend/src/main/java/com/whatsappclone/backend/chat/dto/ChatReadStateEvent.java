package com.whatsappclone.backend.chat.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Broadcast when a user marks a chat read, so that user's other sessions drop the unread badge.
 *
 * <p>This goes to the user's own topic rather than the chat's. An unread count is a per-user
 * projection — it is computed from that user's own {@code message_receipts} rows — so telling the
 * other members would inform them of a number they have no use for. What other members need to
 * know that they have read is carried by the per-message receipts instead.
 *
 * @param chatId the chat that was marked read
 * @param userId the user who marked it, which is always the subscriber
 * @param lastReadAt the instant persisted as the participant's read watermark
 */
public record ChatReadStateEvent(UUID chatId, UUID userId, Instant lastReadAt) {
}