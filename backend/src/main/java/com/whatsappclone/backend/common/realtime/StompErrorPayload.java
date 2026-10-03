package com.whatsappclone.backend.common.realtime;

/**
 * A rejected inbound STOMP frame, delivered to the sender's own error topic.
 *
 * <p>Two Spring behaviours make this necessary rather than merely nicer.
 * {@code AbstractMethodMessageHandler.processHandlerMethodException} logs an unhandled exception
 * and returns without rethrowing, so removing the {@code @MessageExceptionHandler} makes failures
 * <em>more</em> silent rather than less — nothing ever reaches the transport. And the transport's
 * own fallback, {@code StompSubProtocolHandler.sendErrorMessage}, emits its ERROR frame and then
 * closes the socket with {@code PROTOCOL_ERROR}, so one malformed SEND would drop the connection.
 * Emitting on an explicit topic keeps the socket up and lets the client react.
 *
 * <p>{@code code} matches the REST {@code ErrorCode} names exactly, so a client branches on one
 * vocabulary regardless of transport, and {@code receipt} echoes the STOMP {@code receipt} header
 * from the offending SEND, which is what lets a client attribute the failure to one specific
 * in-flight message rather than to the connection as a whole.
 *
 * @param code the {@code ErrorCode} name, safe to branch on
 * @param message human-readable detail, may change
 * @param destination the inbound destination that was rejected
 * @param receipt the client's {@code receipt} header value, or null if it sent none
 */
public record StompErrorPayload(String code, String message, String destination, String receipt) {

	/** Convenience for callers that have no receipt to correlate against. */
	public static StompErrorPayload of(String code, String message, String destination) {
		return new StompErrorPayload(code, message, destination, null);
	}
}