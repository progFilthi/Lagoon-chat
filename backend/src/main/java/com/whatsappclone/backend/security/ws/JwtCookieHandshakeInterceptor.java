package com.whatsappclone.backend.security.ws;

import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.stereotype.Component;

/**
 * Captures the session JWT from the WebSocket handshake and parks it in the session attributes.
 *
 * <p>This exists so the browser can hold the token in an httpOnly cookie. A STOMP CONNECT frame is
 * built in JavaScript, and the WebSocket API cannot set request headers, so the only header-based
 * route to the token is a native STOMP {@code Authorization} header — which means JavaScript has to
 * be able to read the token, defeating httpOnly. Cookies are the opposite: unreadable from
 * JavaScript, but sent automatically on the WebSocket upgrade request. So the token arrives here, at
 * the handshake, where nothing client-side had to expose it.
 *
 * <p>The value lands in the session attributes map, which is mutable and survives every frame on the
 * connection, and {@code StompAuthChannelInterceptor} reads it back from there when the CONNECT frame
 * carries no {@code Authorization} header. An explicit header still wins, which is what keeps
 * non-browser clients and the existing tests working unchanged.
 *
 * <p>Only the one cookie named here is read. The attribute is never logged, and never leaves the
 * server: the same value would have been in the cookie anyway.
 */
@Component
public class JwtCookieHandshakeInterceptor implements HandshakeInterceptor {

	/** Must match {@code SESSION_COOKIE} in the frontend's {@code lib/api/backend.ts}. */
	public static final String COOKIE_NAME = "lagoon_session";

	/** Session-attributes key the resolved token is parked under. */
	public static final String ATTRIBUTE = "whatsapp.jwt";

	@Override
	public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
			WebSocketHandler wsHandler, Map<String, Object> attributes) {
		String token = extractToken(request.getHeaders().getFirst(HttpHeaders.COOKIE));
		if (token != null) {
			attributes.put(ATTRIBUTE, token);
		}
		return true;
	}

	@Override
	public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
			WebSocketHandler wsHandler, Exception exception) {
		// Nothing to do. The attribute is scoped to the session and discarded with it.
	}

	/**
	 * Pulls one cookie value out of a raw {@code Cookie} header.
	 *
	 * <p>Splits on {@code ;} then the first {@code =} of each pair, because a JWT is base64url and may
	 * legitimately contain {@code -} and {@code _} but never {@code ;} — so no amount of
	 * base64-decoded structure is needed to find the boundary, and a token that happens to contain
	 * an {@code =} would not be truncated. An unencoded comma is not treated as a separator, which
	 * would corrupt any token containing one.
	 *
	 * @return the token, or {@code null} if the cookie is absent or blank
	 */
	static String extractToken(String cookieHeader) {
		if (cookieHeader == null || cookieHeader.isBlank()) {
			return null;
		}
		for (String pair : cookieHeader.split(";")) {
			String trimmed = pair.trim();
			int separator = trimmed.indexOf('=');
			if (separator < 0) {
				continue;
			}
			if (trimmed.substring(0, separator).equals(COOKIE_NAME)) {
				String value = trimmed.substring(separator + 1).trim();
				return value.isEmpty() ? null : value;
			}
		}
		return null;
	}

}
