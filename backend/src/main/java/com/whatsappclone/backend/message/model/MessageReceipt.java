package com.whatsappclone.backend.message.model;

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
@Table(name = "message_receipts", uniqueConstraints = {
		@UniqueConstraint(name = "uq_message_receipts_message_user", columnNames = { "message_id", "user_id" })
}, indexes = {
		@Index(name = "idx_message_receipts_user_status", columnList = "user_id, status"),
		@Index(name = "idx_message_receipts_message", columnList = "message_id")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter(AccessLevel.PACKAGE)
@ToString(exclude = { "message", "recipient" })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MessageReceipt {

	@Id
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "message_id", nullable = false)
	private Message message;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User recipient;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private MessageStatus status;

	@Column(name = "delivered_at")
	private Instant deliveredAt;

	@Column(name = "read_at")
	private Instant readAt;

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@LastModifiedDate
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	public static MessageReceipt pending(Message message, User recipient) {
		MessageReceipt receipt = new MessageReceipt();
		receipt.id = UUID.randomUUID();
		receipt.message = message;
		receipt.recipient = recipient;
		receipt.status = MessageStatus.SENT;
		return receipt;
	}

	public void markDelivered(Instant timestamp) {
		if (status == MessageStatus.SENT) {
			this.status = MessageStatus.DELIVERED;
		}
		if (this.deliveredAt == null) {
			this.deliveredAt = timestamp;
		}
	}

	public void markRead(Instant timestamp) {
		this.status = MessageStatus.READ;
		if (this.deliveredAt == null) {
			this.deliveredAt = timestamp;
		}
		if (this.readAt == null) {
			this.readAt = timestamp;
		}
	}
}