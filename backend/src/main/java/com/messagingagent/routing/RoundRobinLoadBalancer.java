package com.messagingagent.routing;

import com.messagingagent.model.Device;
import com.messagingagent.model.DeviceGroup;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe round-robin load balancer across Android devices in a group.
 *
 * For each DeviceGroup, maintains a counter that is atomically incremented
 * on each dispatch request. Online devices are selected in a rotating fashion.
 */
@Component
public class RoundRobinLoadBalancer {

    // groupId -> counter
    private final Map<Long, AtomicInteger> counters = new ConcurrentHashMap<>();

    /**
     * Select the next available online device in the group (round-robin).
     *
     * @param group  the DeviceGroup (virtual SMSC)
     * @param onlineDevices list of currently ONLINE devices in the group
     * @return selected device, or empty if none are online
     */
    public Optional<Device> selectDevice(DeviceGroup group, List<Device> onlineDevices) {
        if (onlineDevices == null || onlineDevices.isEmpty()) {
            return Optional.empty();
        }

        AtomicInteger counter = counters.computeIfAbsent(group.getId(), k -> new AtomicInteger(0));
        // atomically get-and-increment, mod size
        int idx = counter.getAndIncrement() % onlineDevices.size();
        return Optional.of(onlineDevices.get(idx));
    }

    /** Reset counter for a group (e.g. when group config changes). */
    public void resetCounter(Long groupId) {
        counters.remove(groupId);
    }
}
