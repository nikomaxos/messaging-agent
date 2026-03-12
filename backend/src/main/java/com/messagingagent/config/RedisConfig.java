package com.messagingagent.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    /**
     * String-keyed RedisTemplate for SMPP correlation entries.
     * Key format: "smpp:session:{correlationId}" → sessionId string
     * Key format: "smpp:source:{correlationId}"  → source address string
     *
     * Named explicitly so that SmppServerService can @Qualifier("smppCorrelationRedisTemplate").
     * Does NOT use @Primary to avoid interfering with Spring Boot's auto-configured
     * reactive Lettuce connection factory (used by RedisReactiveHealthIndicator).
     */
    @Bean(name = "smppCorrelationRedisTemplate")
    public RedisTemplate<String, String> smppCorrelationRedisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, String> tpl = new RedisTemplate<>();
        tpl.setConnectionFactory(factory);
        tpl.setKeySerializer(new StringRedisSerializer());
        tpl.setValueSerializer(new StringRedisSerializer());
        tpl.setHashKeySerializer(new StringRedisSerializer());
        tpl.setHashValueSerializer(new StringRedisSerializer());
        return tpl;
    }
}
