package com.messagingagent.smpp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = "com.messagingagent")
public class SmppEdgeApplication {
    public static void main(String[] args) {
        SpringApplication.run(SmppEdgeApplication.class, args);
    }
}
