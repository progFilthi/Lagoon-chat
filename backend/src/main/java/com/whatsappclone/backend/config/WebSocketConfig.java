package com.whatsappclone.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

	private static final String[] BROKER_PREFIXES = { "/topic", "/queue" };
	private static final String APPLICATION_PREFIX = "/app";
	private static final String USER_PREFIX = "/user";
	private static final String ENDPOINT = "/ws";

	private final com.whatsappclone.backend.security.ws.StompAuthChannelInterceptor stompAuthChannelInterceptor;

	public WebSocketConfig(
			com.whatsappclone.backend.security.ws.StompAuthChannelInterceptor stompAuthChannelInterceptor) {
		this.stompAuthChannelInterceptor = stompAuthChannelInterceptor;
	}

	@Override
	public void registerStompEndpoints(StompEndpointRegistry registry) {
		registry.addEndpoint(ENDPOINT).setAllowedOriginPatterns("*");
	}

	@Override
	public void configureMessageBroker(MessageBrokerRegistry registry) {
		registry.enableSimpleBroker(BROKER_PREFIXES);
		registry.setApplicationDestinationPrefixes(APPLICATION_PREFIX);
		registry.setUserDestinationPrefix(USER_PREFIX);
	}

	@Override
	public void configureClientInboundChannel(ChannelRegistration registration) {
		registration.interceptors(stompAuthChannelInterceptor);
	}
}