package com.messagingagent.device;

import com.messagingagent.model.Device;
import com.messagingagent.repository.DeviceRepository;
import com.messagingagent.repository.MessageLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes per-device performance scores based on delivery latency and success rate.
 * Provides two windows: 2-hour (short-term) and 7-day (long-term).
 */
@Service
@RestController
@RequestMapping("/api/devices")
@RequiredArgsConstructor
@Slf4j
public class DevicePerformanceService {

    private final DeviceRepository deviceRepository;
    private final MessageLogRepository messageLogRepository;

    public record DeviceScore(double score, double avgLatencySeconds, double successRate, long totalDispatched) {}
    public record DualScore(DeviceScore score2h, DeviceScore score7d) {}

    /**
     * Compute performance score for a single device over a given rolling window.
     */
    public DeviceScore computeScore(Long deviceId, int hours) {
        Instant since = Instant.now().minus(hours, ChronoUnit.HOURS);

        long delivered = messageLogRepository.countDeliveredByDevice(deviceId, since);
        long failed = messageLogRepository.countFailedByDevice(deviceId, since);
        long totalDispatched = messageLogRepository.countDispatchedByDevice(deviceId, since);

        double successRate = (delivered + failed) > 0
                ? (double) delivered / (delivered + failed)
                : 1.0;

        double avgLatency = 0;
        if (delivered > 0) {
            avgLatency = messageLogRepository.avgDeliveryLatencySeconds(deviceId, since);
        }

        double speedScore = Math.max(0, 100.0 - avgLatency * 2.0);
        double score = (successRate * 50.0) + (speedScore / 100.0 * 50.0);

        return new DeviceScore(
                Math.round(score * 10.0) / 10.0,
                Math.round(avgLatency * 10.0) / 10.0,
                Math.round(successRate * 1000.0) / 1000.0,
                totalDispatched
        );
    }

    /** Short-term 2h score for load balancer tiebreaker. */
    public DeviceScore computeScore(Long deviceId) {
        return computeScore(deviceId, 2);
    }

    @GetMapping("/performance")
    public Map<Long, DualScore> getAllScores() {
        List<Device> devices = deviceRepository.findAll();
        Map<Long, DualScore> scores = new HashMap<>();
        for (Device d : devices) {
            scores.put(d.getId(), new DualScore(
                    computeScore(d.getId(), 2),
                    computeScore(d.getId(), 168)  // 7 days = 168 hours
            ));
        }
        return scores;
    }
}
