package com.whatsappclone.backend.media.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Authorisation is by message, never by key: the service checks that the caller belongs to the chat
 * that message belongs to, then signs a URL for that message's object. Handing out a key instead
 * would make every object in the bucket fetchable by anyone who ever saw a key.
 */
public record DownloadUrlRequest(@NotNull(message = "messageId is required") UUID messageId) {
}
