package com.whatsappclone.backend.presence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PresenceEvent(UUID userId, String username, boolean online, Instant lastSeen,
		List<UUID> chatIds) {
}