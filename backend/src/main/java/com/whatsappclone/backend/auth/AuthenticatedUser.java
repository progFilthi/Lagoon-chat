package com.whatsappclone.backend.auth;

import java.util.UUID;

public record AuthenticatedUser(UUID id, String username) {
}