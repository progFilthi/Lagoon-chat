package com.whatsappclone.backend.chat.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "chats", indexes = {
		@Index(name = "idx_chats_last_message_at", columnList = "last_message_at")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter(AccessLevel.PACKAGE)
@ToString
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Chat {

	@Id
	private UUID id;

	@Column(name = "is_group", nullable = false)
	private boolean group;

	@Column(name = "group_name", length = 120)
	private String groupName;

	@Column(name = "group_avatar_url", length = 500)
	private String groupAvatarUrl;

	@Column(name = "created_by")
	private UUID createdBy;

	@Column(name = "last_message_at")
	private Instant lastMessageAt;

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@LastModifiedDate
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	public static Chat direct(UUID createdBy) {
		Chat chat = new Chat();
		chat.id = UUID.randomUUID();
		chat.group = false;
		chat.createdBy = createdBy;
		return chat;
	}

	public static Chat group(String groupName, UUID createdBy) {
		Chat chat = new Chat();
		chat.id = UUID.randomUUID();
		chat.group = true;
		chat.groupName = groupName;
		chat.createdBy = createdBy;
		return chat;
	}

	public void touch(Instant messageTime) {
		if (messageTime != null && (lastMessageAt == null || messageTime.isAfter(lastMessageAt))) {
			this.lastMessageAt = messageTime;
		}
	}
}