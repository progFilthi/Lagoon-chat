package com.whatsappclone.backend.user.model;

import com.whatsappclone.backend.common.crypto.HashUtils;
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
@Table(name = "users", indexes = {
		@Index(name = "idx_users_phone_number_hash", columnList = "phone_number_hash")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter(AccessLevel.PACKAGE)
@ToString(exclude = "passwordHash")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

	@Id
	private UUID id;

	@Column(name = "phone_number", nullable = false, unique = true, length = 20)
	private String phoneNumber;

	@Column(name = "phone_number_hash", nullable = false, unique = true, length = 64)
	private String phoneNumberHash;

	@Column(name = "username", nullable = false, unique = true, length = 32)
	private String username;

	@Column(name = "password_hash", nullable = false, length = 100)
	private String passwordHash;

	@Column(name = "profile_picture_url", length = 500)
	private String profilePictureUrl;

	@Column(name = "about", length = 140)
	private String about;

	@Column(name = "last_seen")
	private Instant lastSeen;

	@Column(name = "is_online", nullable = false)
	private boolean online;

	@Column(name = "is_enabled", nullable = false)
	private boolean enabled = true;

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@LastModifiedDate
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	public static User register(String phoneNumber, String username, String passwordHash) {
		User user = new User();
		user.id = UUID.randomUUID();
		user.setPhoneNumber(phoneNumber);
		user.username = username;
		user.passwordHash = passwordHash;
		user.online = false;
		user.enabled = true;
		return user;
	}

	public void setPhoneNumber(String phoneNumber) {
		this.phoneNumber = phoneNumber;
		this.phoneNumberHash = HashUtils.sha256Hex(phoneNumber);
	}

	public void markOnline() {
		this.online = true;
		this.lastSeen = Instant.now();
	}

	public void markOffline() {
		this.online = false;
		this.lastSeen = Instant.now();
	}
}