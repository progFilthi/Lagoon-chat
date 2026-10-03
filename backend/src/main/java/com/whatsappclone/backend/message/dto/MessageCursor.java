package com.whatsappclone.backend.message.dto;

import com.whatsappclone.backend.common.exception.AppException;
import com.whatsappclone.backend.common.exception.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

public record MessageCursor(Instant createdAt, UUID id) {

	public String encode() {
		String raw = createdAt.toEpochMilli() + "|" + id;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	public static MessageCursor decode(String cursor) {
		try {
			String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
			int separator = raw.indexOf('|');
			if (separator < 0) {
				throw new IllegalArgumentException("missing separator");
			}
			return new MessageCursor(Instant.ofEpochMilli(Long.parseLong(raw.substring(0, separator))),
					UUID.fromString(raw.substring(separator + 1)));
		}
		catch (RuntimeException e) {
			throw new AppException(ErrorCode.VALIDATION_ERROR, "cursor is malformed");
		}
	}
}