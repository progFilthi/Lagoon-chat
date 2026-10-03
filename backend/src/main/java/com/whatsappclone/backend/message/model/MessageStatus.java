package com.whatsappclone.backend.message.model;

public enum MessageStatus {
	SENT,
	DELIVERED,
	READ;

	public boolean isAtLeast(MessageStatus other) {
		return this.ordinal() >= other.ordinal();
	}
}