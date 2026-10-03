package com.whatsappclone.backend.auth;

import com.whatsappclone.backend.common.exception.AppException;
import com.whatsappclone.backend.common.exception.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.io.DecodingException;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

@Service
public class JwtService {

	private static final String CLAIM_USERNAME = "username";
	private static final String CLAIM_TOKEN_TYPE = "typ";

	private final SecretKey signingKey;
	private final String issuer;
	private final Duration accessTokenTtl;

	public JwtService(@Value("${app.jwt.secret}") String secret, @Value("${app.jwt.issuer}") String issuer,
			@Value("${app.jwt.access-token-ttl}") Duration accessTokenTtl) {
		this.signingKey = buildKey(secret);
		this.issuer = issuer;
		this.accessTokenTtl = accessTokenTtl;
	}

	public String generateAccessToken(UUID userId, String username) {
		Instant now = Instant.now();
		return Jwts.builder()
				.issuer(issuer)
				.subject(userId.toString())
				.claim(CLAIM_USERNAME, username)
				.claim(CLAIM_TOKEN_TYPE, "access")
				.issuedAt(Date.from(now))
				.expiration(Date.from(now.plus(accessTokenTtl)))
				.signWith(signingKey)
				.compact();
	}

	public UUID extractUserId(String token) {
		return UUID.fromString(extractClaims(token).getSubject());
	}

	public String extractUsername(String token) {
		return extractClaims(token).get(CLAIM_USERNAME, String.class);
	}

	public long expiresInSeconds() {
		return accessTokenTtl.toSeconds();
	}

	public boolean isValid(String token) {
		try {
			extractClaims(token);
			return true;
		}
		catch (AppException e) {
			return false;
		}
	}

	private Claims extractClaims(String token) {
		try {
			return Jwts.parser()
					.verifyWith(signingKey)
					.requireIssuer(issuer)
					.build()
					.parseSignedClaims(token)
					.getPayload();
		}
		catch (ExpiredJwtException e) {
			throw new AppException(ErrorCode.UNAUTHORIZED, "Access token has expired");
		}
		catch (JwtException | IllegalArgumentException e) {
			throw new AppException(ErrorCode.UNAUTHORIZED, "Access token is invalid");
		}
	}

	private static SecretKey buildKey(String secret) {
		byte[] bytes;
		try {
			bytes = Decoders.BASE64.decode(secret);
		}
		catch (DecodingException e) {
			bytes = secret.getBytes(StandardCharsets.UTF_8);
		}
		if (bytes.length < 32) {
			throw new IllegalStateException("app.jwt.secret must decode to at least 256 bits for HS256");
		}
		return Keys.hmacShaKeyFor(bytes);
	}
}