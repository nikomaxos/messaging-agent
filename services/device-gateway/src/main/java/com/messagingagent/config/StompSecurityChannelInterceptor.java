package com.messagingagent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
@Slf4j
public class StompSecurityChannelInterceptor implements ChannelInterceptor {

    @Autowired
    private StringRedisTemplate redisTemplate;

    private static final long MAX_SKEW_SECONDS = 30;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            
            // 1. Validate Timestamp Skew
            String timestampStr = accessor.getFirstNativeHeader("X-Timestamp");
            if (timestampStr == null) {
                log.warn("STOMP Connect rejected: Missing X-Timestamp");
                throw new IllegalArgumentException("Missing X-Timestamp");
            }
            try {
                long timestampMs = Long.parseLong(timestampStr);
                Instant clientTime = Instant.ofEpochMilli(timestampMs);
                long skew = Math.abs(Duration.between(clientTime, Instant.now()).getSeconds());
                if (skew > MAX_SKEW_SECONDS) {
                    log.warn("STOMP Connect rejected: Timestamp skew too large ({}s)", skew);
                    throw new IllegalArgumentException("Timestamp skew too large. Possible replay attack.");
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid X-Timestamp format");
            }

            // 2. Validate Nonce (Replay Attack Protection)
            String nonce = accessor.getFirstNativeHeader("X-Nonce");
            if (nonce == null) {
                log.warn("STOMP Connect rejected: Missing X-Nonce");
                throw new IllegalArgumentException("Missing X-Nonce");
            }
            String nonceKey = "stomp:nonce:" + nonce;
            Boolean isNewNonce = redisTemplate.opsForValue().setIfAbsent(nonceKey, "used", Duration.ofSeconds(MAX_SKEW_SECONDS * 2));
            if (Boolean.FALSE.equals(isNewNonce)) {
                log.warn("STOMP Connect rejected: Nonce already used (Replay Attack)");
                throw new IllegalArgumentException("Nonce already used");
            }

            // 3. Validate Hardware Keystore Signature
            String signature = accessor.getFirstNativeHeader("X-Keystore-Sig");
            if (signature == null) {
                log.warn("STOMP Connect rejected: Missing X-Keystore-Sig");
                throw new IllegalArgumentException("Missing X-Keystore-Sig");
            }
            // TODO: Retrieve device's registered Public Key from Database using device UUID
            // byte[] publicKey = deviceRepository.findPublicKey(deviceId);
            // boolean isValid = KeystoreValidator.verifySignature(nonce, timestampStr, signature, publicKey);
            // if (!isValid) throw new IllegalArgumentException("Invalid Hardware Keystore Signature");
            
            log.info("STOMP Connection validated successfully for Nonce: {}", nonce);
        }

        return message;
    }
}
