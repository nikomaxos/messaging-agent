package com.messagingagent.admin;

import com.messagingagent.model.MessageLog;
import com.messagingagent.repository.DeviceRepository;
import com.messagingagent.repository.MessageLogRepository;
import com.messagingagent.smpp.SmscConnectionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadMXBean;
import java.io.File;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Exposes real-time system health metrics for the Infrastructure Monitoring Dashboard.
 */
@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
@Slf4j
public class SystemHealthController {

    private final DeviceRepository deviceRepository;
    private final MessageLogRepository messageLogRepository;

    @GetMapping("/health")
    public Map<String, Object> getSystemHealth() {
        Map<String, Object> health = new LinkedHashMap<>();

        // ── OS-level metrics ──────────────────────────────────────────────
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        Map<String, Object> os = new LinkedHashMap<>();
        os.put("name", osBean.getName());
        os.put("arch", osBean.getArch());
        os.put("processors", osBean.getAvailableProcessors());
        os.put("loadAverage", osBean.getSystemLoadAverage());

        // Try to get detailed CPU/Memory from com.sun.management (available on HotSpot)
        if (osBean instanceof com.sun.management.OperatingSystemMXBean sunOs) {
            os.put("cpuUsage", Math.round(sunOs.getCpuLoad() * 10000.0) / 100.0); // percent with 2 decimals
            os.put("processCpuUsage", Math.round(sunOs.getProcessCpuLoad() * 10000.0) / 100.0);
            os.put("totalPhysicalMemory", sunOs.getTotalMemorySize());
            os.put("freePhysicalMemory", sunOs.getFreeMemorySize());
            os.put("usedPhysicalMemory", sunOs.getTotalMemorySize() - sunOs.getFreeMemorySize());
        }
        health.put("os", os);

        // ── JVM metrics ───────────────────────────────────────────────────
        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
        Map<String, Object> jvm = new LinkedHashMap<>();
        jvm.put("heapUsed", memBean.getHeapMemoryUsage().getUsed());
        jvm.put("heapMax", memBean.getHeapMemoryUsage().getMax());
        jvm.put("heapCommitted", memBean.getHeapMemoryUsage().getCommitted());
        jvm.put("nonHeapUsed", memBean.getNonHeapMemoryUsage().getUsed());
        
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        jvm.put("threadCount", threadBean.getThreadCount());
        jvm.put("peakThreadCount", threadBean.getPeakThreadCount());
        
        jvm.put("uptimeMs", ManagementFactory.getRuntimeMXBean().getUptime());
        health.put("jvm", jvm);

        // ── Disk usage ────────────────────────────────────────────────────
        List<Map<String, Object>> disks = new ArrayList<>();
        for (File root : File.listRoots()) {
            Map<String, Object> disk = new LinkedHashMap<>();
            disk.put("path", root.getAbsolutePath());
            disk.put("totalSpace", root.getTotalSpace());
            disk.put("freeSpace", root.getFreeSpace());
            disk.put("usableSpace", root.getUsableSpace());
            disk.put("usedSpace", root.getTotalSpace() - root.getFreeSpace());
            disks.add(disk);
        }
        health.put("disks", disks);

        // ── Device fleet summary ──────────────────────────────────────────
        Map<String, Object> fleet = new LinkedHashMap<>();
        try {
            long total = deviceRepository.count();
            long online = deviceRepository.countByStatus(com.messagingagent.model.Device.Status.ONLINE);
            long offline = deviceRepository.countByStatus(com.messagingagent.model.Device.Status.OFFLINE);
            long busy = deviceRepository.countByStatus(com.messagingagent.model.Device.Status.BUSY);
            fleet.put("total", total);
            fleet.put("online", online);
            fleet.put("offline", offline);
            fleet.put("busy", busy);
        } catch (Exception e) {
            fleet.put("error", e.getMessage());
        }
        health.put("fleet", fleet);

        // ── Message pipeline (last hour) ──────────────────────────────────
        Map<String, Object> pipeline = new LinkedHashMap<>();
        try {
            Instant oneHourAgo = Instant.now().minus(1, ChronoUnit.HOURS);
            Instant startOfDay = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS).toInstant();
            pipeline.put("receivedLastHour", messageLogRepository.countByStatusAndCreatedAtAfter(MessageLog.Status.RECEIVED, oneHourAgo));
            pipeline.put("dispatchedLastHour", messageLogRepository.countByStatusAndCreatedAtAfter(MessageLog.Status.DISPATCHED, oneHourAgo));
            pipeline.put("deliveredLastHour", messageLogRepository.countByStatusAndCreatedAtAfter(MessageLog.Status.DELIVERED, oneHourAgo));
            pipeline.put("failedLastHour", messageLogRepository.countByStatusAndCreatedAtAfter(MessageLog.Status.FAILED, oneHourAgo));
            pipeline.put("deliveredToday", messageLogRepository.countByStatusAndCreatedAtAfter(MessageLog.Status.DELIVERED, startOfDay));
            pipeline.put("failedToday", messageLogRepository.countByStatusAndCreatedAtAfter(MessageLog.Status.FAILED, startOfDay));
        } catch (Exception e) {
            pipeline.put("error", e.getMessage());
        }
        health.put("pipeline", pipeline);

        health.put("timestamp", Instant.now().toString());
        return health;
    }
}
