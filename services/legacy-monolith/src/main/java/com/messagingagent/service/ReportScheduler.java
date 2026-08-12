package com.messagingagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.messagingagent.model.MessageLog;
import com.messagingagent.model.ScheduledReport;
import com.messagingagent.repository.DeviceRepository;
import com.messagingagent.repository.MessageLogRepository;
import com.messagingagent.repository.ScheduledReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Daily scheduled report — runs at 06:00 UTC.
 * Computes 24h stats and stores as JSON report.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReportScheduler {

    private final MessageLogRepository messageLogRepository;
    private final DeviceRepository deviceRepository;
    private final ScheduledReportRepository reportRepository;
    private final PlatformHealthService healthService;
    private final ObjectMapper objectMapper;

    @Scheduled(cron = "0 0 6 * * *") // daily at 06:00 UTC
    public void generateDailyReport() {
        try {
            Instant since = Instant.now().minusSeconds(86400);

            long totalMessages = messageLogRepository.countTotalSince(since);
            long delivered = messageLogRepository.countByStatusesSince(
                    List.of(MessageLog.Status.DELIVERED), since);
            long failed = messageLogRepository.countByStatusesSince(
                    List.of(MessageLog.Status.FAILED, MessageLog.Status.RCS_FAILED), since);
            long queued = messageLogRepository.countByStatusesSince(
                    List.of(MessageLog.Status.QUEUED), since);

            double deliveryRate = totalMessages > 0 ? (double) delivered / totalMessages * 100 : 0;

            long totalDevices = deviceRepository.count();
            long onlineDevices = healthService.getOnlineDeviceCount();

            Map<String, Object> report = new LinkedHashMap<>();
            report.put("period", "24h");
            report.put("totalMessages", totalMessages);
            report.put("delivered", delivered);
            report.put("failed", failed);
            report.put("queued", queued);
            report.put("deliveryRate", Math.round(deliveryRate * 100.0) / 100.0);
            report.put("totalDevices", totalDevices);
            report.put("onlineDevices", onlineDevices);
            report.put("offlineDevices", totalDevices - onlineDevices);

            String json = objectMapper.writeValueAsString(report);
            reportRepository.save(ScheduledReport.builder()
                    .period("DAILY")
                    .reportJson(json)
                    .build());

            log.info("Daily report generated: {} msgs, {}% delivery rate", totalMessages, Math.round(deliveryRate));
        } catch (Exception e) {
            log.error("Failed to generate daily report", e);
        }
    }
}
