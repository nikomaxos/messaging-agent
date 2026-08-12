package com.messagingagent.smpp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ComponentScan(basePackages = "com.messagingagent")
public class SmppEdgeApplication {
    public static void main(String[] args) {
        SpringApplication.run(SmppEdgeApplication.class, args);
    }
}
