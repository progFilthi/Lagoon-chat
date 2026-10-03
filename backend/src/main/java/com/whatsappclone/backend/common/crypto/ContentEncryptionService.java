package com.whatsappclone.backend.common.crypto;

import com.whatsappclone.backend.common.exception.AppException;
import com.whatsappclone.backend.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class ContentEncryptionService {

	private static final int IV_LENGTH = 12;
	private static final int TAG_LENGTH_BITS = 128;

	private final SecretKeySpec key;
	private final SecureRandom secureRandom = new SecureRandom();

	public ContentEncryptionService(@Value("${app.encryption.content-key}") String contentKey) {
		this.key = new SecretKeySpec(HashUtils.sha256Hex(contentKey).substring(0, 32)
				.getBytes(StandardCharsets.US_ASCII), "AES");
	}

	public String encrypt(String plaintext) {
		if (plaintext == null) {
			return null;
		}
		try {
			byte[] iv = new byte[IV_LENGTH];
			secureRandom.nextBytes(iv);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
			byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
			byte[] combined = new byte[iv.length + ciphertext.length];
			System.arraycopy(iv, 0, combined, 0, iv.length);
			System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
			return Base64.getEncoder().encodeToString(combined);
		}
		catch (GeneralSecurityException e) {
			throw new AppException(ErrorCode.INTERNAL_ERROR, "Failed to encrypt message content");
		}
	}

	public String decrypt(String encoded) {
		if (encoded == null) {
			return null;
		}
		try {
			byte[] combined = Base64.getDecoder().decode(encoded);
			if (combined.length <= IV_LENGTH) {
				throw new AppException(ErrorCode.INTERNAL_ERROR, "Malformed encrypted payload");
			}
			byte[] iv = new byte[IV_LENGTH];
			System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
			byte[] plaintext = cipher.doFinal(combined, IV_LENGTH, combined.length - IV_LENGTH);
			return new String(plaintext, StandardCharsets.UTF_8);
		}
		catch (IllegalArgumentException | GeneralSecurityException e) {
			throw new AppException(ErrorCode.INTERNAL_ERROR, "Failed to decrypt message content");
		}
	}
}