package com.messagingagent.rcs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = {org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class})
@ComponentScan(basePackages = {"com.messagingagent"})
@EnableScheduling
public class RcsMautrixApplication {
    public static void main(String[] args) {
        SpringApplication.run(RcsMautrixApplication.class, args);
    }
}
