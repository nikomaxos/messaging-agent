package com.messagingagent.config;

import com.messagingagent.kafka.SmsDeliveryResultEvent;
import com.messagingagent.kafka.SmsInboundEvent;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.support.converter.RecordMessageConverter;
import org.springframework.kafka.support.converter.StringJsonMessageConverter;

@Configuration
public class KafkaConfig {

    @Bean
    public NewTopic smsInboundTopic() {
        return TopicBuilder.name("sms.inbound")
                .partitions(6)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic smsDeliveryResultTopic() {
        return TopicBuilder.name("sms.delivery.result")
                .partitions(6)
                .replicas(1)
                .build();
    }

    @Bean
    public RecordMessageConverter messageConverter() {
        return new StringJsonMessageConverter();
    }
}
