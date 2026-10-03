package com.whatsappclone.backend.config;

import com.whatsappclone.backend.security.ws.StompAuthChannelInterceptor;
import org.springframework.beans.factory.annotation.Value;
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
	private static final String ENDPOINT = "/ws";

	private final StompAuthChannelInterceptor stompAuthChannelInterceptor;
	private final String relayHost;
	private final int relayPort;
	private final String relayLogin;
	private final String relayPasscode;
	private final String relayVirtualHost;
	private final long heartbeatSendInterval;
	private final long heartbeatReceiveInterval;

	public WebSocketConfig(StompAuthChannelInterceptor stompAuthChannelInterceptor,
			@Value("${app.messaging.host}") String relayHost, @Value("${app.messaging.port}") int relayPort,
			@Value("${app.messaging.login}") String relayLogin,
			@Value("${app.messaging.passcode}") String relayPasscode,
			@Value("${app.messaging.virtual-host}") String relayVirtualHost,
			@Value("${app.messaging.heartbeat-send-interval}") long heartbeatSendInterval,
			@Value("${app.messaging.heartbeat-receive-interval}") long heartbeatReceiveInterval) {
		this.stompAuthChannelInterceptor = stompAuthChannelInterceptor;
		this.relayHost = relayHost;
		this.relayPort = relayPort;
		this.relayLogin = relayLogin;
		this.relayPasscode = relayPasscode;
		this.relayVirtualHost = relayVirtualHost;
		this.heartbeatSendInterval = heartbeatSendInterval;
		this.heartbeatReceiveInterval = heartbeatReceiveInterval;
	}

	/**
	 * Delegates the broker to RabbitMQ instead of an in-memory simple broker. With the simple
	 * broker each instance only delivers to sockets attached to itself, so a user connected to
	 * instance B never sees a message published on instance A. The relay makes every instance a
	 * subscriber of the same exchanges.
	 *
	 * The relay speaks STOMP to the broker's stomp plugin on port 61613, not AMQP to 5672.
	 */
	@Override
	public void configureMessageBroker(MessageBrokerRegistry registry) {
		registry.enableStompBrokerRelay(BROKER_PREFIXES)
				.setRelayHost(relayHost)
				.setRelayPort(relayPort)
				.setClientLogin(relayLogin)
				.setClientPasscode(relayPasscode)
				.setVirtualHost(relayVirtualHost)
				.setSystemLogin(relayLogin)
				.setSystemPasscode(relayPasscode)
				.setSystemHeartbeatSendInterval(heartbeatSendInterval)
				.setSystemHeartbeatReceiveInterval(heartbeatReceiveInterval);
		registry.setApplicationDestinationPrefixes(APPLICATION_PREFIX);
	}

	@Override
	public void registerStompEndpoints(StompEndpointRegistry registry) {
		registry.addEndpoint(ENDPOINT).setAllowedOriginPatterns("*");
	}

	@Override
	public void configureClientInboundChannel(ChannelRegistration registration) {
		registration.interceptors(stompAuthChannelInterceptor);
	}
}