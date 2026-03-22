package com.messagingagent.routing;

import com.messagingagent.device.DevicePerformanceService;
import com.messagingagent.model.Device;
import com.messagingagent.model.DeviceGroup;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fair load balancer across Android devices in a group.
 *
 * Uses per-device dispatch counters to always select the ONLINE device
 * with the lowest number of dispatches. When counts are tied, uses the
 * performance score (delivery speed + success rate) as tiebreaker.
 */
@Component
public class RoundRobinLoadBalancer {

    // deviceId -> total dispatches
    private final Map<Long, AtomicLong> dispatchCounts = new ConcurrentHashMap<>();

    private final DevicePerformanceService performanceService;

    public RoundRobinLoadBalancer(DevicePerformanceService performanceService) {
        this.performanceService = performanceService;
    }

    /**
     * Select the best device: least dispatches first, then highest performance score.
     */
    public Optional<Device> selectDevice(DeviceGroup group, List<Device> onlineDevices) {
        if (onlineDevices == null || onlineDevices.isEmpty()) {
            return Optional.empty();
        }

        Device selected = onlineDevices.stream()
                .min(Comparator
                        .comparingLong((Device d) -> getCount(d.getId()))
                        .thenComparing((Device d) -> {
                            try {
                                return -performanceService.computeScore(d.getId()).score();
                            } catch (Exception e) {
                                return 0.0;
                            }
                        }))
                .orElse(onlineDevices.get(0));

        return Optional.of(selected);
    }

    /** Record that a message was dispatched to this device. */
    public void recordDispatch(Long deviceId) {
        dispatchCounts.computeIfAbsent(deviceId, k -> new AtomicLong(0)).incrementAndGet();
    }

    /** Get the current dispatch count for a device. */
    public long getCount(Long deviceId) {
        AtomicLong counter = dispatchCounts.get(deviceId);
        return counter != null ? counter.get() : 0;
    }

    /** Reset counter for a group (e.g. when group config changes). */
    public void resetCounter(Long groupId) {
        dispatchCounts.clear();
    }
}
