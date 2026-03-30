package com.messagingagent.service;

import com.messagingagent.dto.SystemLogEvent;
import com.messagingagent.model.SystemLog;
import com.messagingagent.repository.SystemLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;

import java.time.temporal.ChronoUnit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class SystemLogService {

    private final SystemLogRepository systemLogRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public void logAndBroadcast(String level, String device, String event, String detail) {
        try {
            SystemLog sysLog = SystemLog.builder()
                    .level(level)
                    .device(device)
                    .event(event)
                    .detail(detail)
                    .createdAt(Instant.now())
                    .build();
            systemLogRepository.save(sysLog);

            SystemLogEvent dto = SystemLogEvent.builder()
                    .level(level)
                    .device(device)
                    .event(event)
                    .detail(detail)
                    .build();
            messagingTemplate.convertAndSend("/topic/logs", dto);
        } catch (Exception e) {
            log.error("Failed to persist and broadcast system log", e);
        }
    }

    @Scheduled(cron = "0 0 2 * * *") // Run daily at 2 AM
    @Transactional
    public void purgeOldLogs() {
        log.info("Starting scheduled system log purge (older than 40 days)");
        Instant cutoff = Instant.now().minus(40, ChronoUnit.DAYS);
        int deleted = systemLogRepository.deleteOlderThan(cutoff);
        log.info("System log purge complete. Deleted {} records.", deleted);
    }
}
