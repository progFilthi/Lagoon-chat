package com.whatsappclone.backend.message.model;

import com.whatsappclone.backend.chat.model.Chat;
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
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "messages", uniqueConstraints = {
		@UniqueConstraint(name = "uq_messages_sender_client", columnNames = { "sender_id", "client_message_id" })
}, indexes = {
		@Index(name = "idx_messages_chat_created", columnList = "chat_id, created_at, id"),
		@Index(name = "idx_messages_sender_created", columnList = "sender_id, created_at")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter(AccessLevel.PACKAGE)
@ToString(exclude = { "chat", "sender", "content", "mediaKey" })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Message {

	@Id
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "chat_id", nullable = false)
	private Chat chat;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "sender_id", nullable = false)
	private User sender;

	@Column(name = "client_message_id", nullable = false, length = 64)
	private String clientMessageId;

	@Column(name = "content", nullable = false, columnDefinition = "text")
	private String content;

	/**
	 * S3 object key for media messages, null for text. The bytes never touch this service; the
	 * bucket stays private and clients fetch a short-lived signed URL instead.
	 */
	@Column(name = "media_key", length = 512)
	private String mediaKey;

	@Enumerated(EnumType.STRING)
	@Column(name = "type", nullable = false, length = 20)
	private MessageType type;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "reply_to_id")
	private Message replyTo;

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	public static Message create(Chat chat, User sender, String clientMessageId, String encryptedContent,
			MessageType type, Message replyTo, String mediaKey) {
		Message message = new Message();
		message.id = UUID.randomUUID();
		message.chat = chat;
		message.sender = sender;
		message.clientMessageId = clientMessageId;
		message.content = encryptedContent;
		message.type = type;
		message.replyTo = replyTo;
		message.mediaKey = mediaKey;
		return message;
	}

	/** True when the message body is an attachment rather than something the user typed. */
	public boolean isMedia() {
		return mediaKey != null;
	}
}