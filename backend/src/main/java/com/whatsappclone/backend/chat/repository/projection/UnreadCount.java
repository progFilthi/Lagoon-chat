package com.whatsappclone.backend.chat.repository.projection;

import java.util.UUID;

public interface UnreadCount {

	UUID getChatId();

	long getUnreadCount();
}