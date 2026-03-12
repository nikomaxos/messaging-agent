package com.messagingagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MessagingAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(MessagingAgentApplication.class, args);
    }
}
