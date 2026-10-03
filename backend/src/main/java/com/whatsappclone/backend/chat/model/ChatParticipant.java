package com.whatsappclone.backend.chat.model;

import com.whatsappclone.backend.user.model.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
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
@Table(name = "chat_participants", uniqueConstraints = {
		@UniqueConstraint(name = "uq_chat_participants_chat_user", columnNames = { "chat_id", "user_id" })
}, indexes = {
		@Index(name = "idx_chat_participants_user", columnList = "user_id"),
		@Index(name = "idx_chat_participants_chat", columnList = "chat_id")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter(AccessLevel.PACKAGE)
@ToString(exclude = { "chat", "user" })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatParticipant {

	@Id
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "chat_id", nullable = false)
	private Chat chat;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@Enumerated(EnumType.STRING)
	@Column(name = "role", nullable = false, length = 20)
	private ChatRole role;

	@Column(name = "joined_at", nullable = false)
	private Instant joinedAt;

	@Column(name = "last_read_at")
	private Instant lastReadAt;

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@LastModifiedDate
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	public static ChatParticipant join(Chat chat, User user, ChatRole role) {
		ChatParticipant participant = new ChatParticipant();
		participant.id = UUID.randomUUID();
		participant.chat = chat;
		participant.user = user;
		participant.role = role;
		participant.joinedAt = Instant.now();
		return participant;
	}

	public boolean isAdmin() {
		return role == ChatRole.ADMIN;
	}

	public void markReadAt(Instant timestamp) {
		if (timestamp != null && (lastReadAt == null || timestamp.isAfter(lastReadAt))) {
			this.lastReadAt = timestamp;
		}
	}
}