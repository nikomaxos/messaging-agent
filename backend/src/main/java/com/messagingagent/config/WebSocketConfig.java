package com.messagingagent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // Simple broker for /topic (broadcasts) and /queue (point-to-point device queues)
        config.enableSimpleBroker("/topic", "/queue");
        // Application destination prefix — devices send to /app/**
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Android devices connect to /ws (no SockJS, raw WebSocket)
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*");
        // Admin panel uses SockJS fallback
        registry.addEndpoint("/ws-admin").setAllowedOriginPatterns("*").withSockJS();
    }
}
