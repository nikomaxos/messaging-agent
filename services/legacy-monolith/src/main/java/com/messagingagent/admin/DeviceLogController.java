package com.messagingagent.admin;

import com.messagingagent.model.Device;
import com.messagingagent.model.DeviceLog;
import com.messagingagent.repository.DeviceLogRepository;
import com.messagingagent.repository.DeviceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.persistence.criteria.Predicate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/logs/device")
@RequiredArgsConstructor
public class DeviceLogController {

    private final DeviceLogRepository logRepository;
    private final DeviceRepository deviceRepository;

    @GetMapping
    public Page<DeviceLog> list(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false)    Long deviceId,
            @RequestParam(required = false)    String level,
            @RequestParam(required = false)    Instant startDate,
            @RequestParam(required = false)    Instant endDate) {

        PageRequest pageable = PageRequest.of(page, Math.min(size, 200), Sort.by(Sort.Direction.DESC, "createdAt"));

        Specification<DeviceLog> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (deviceId != null) {
                predicates.add(cb.equal(root.get("device").get("id"), deviceId));
            }
            if (level != null && !level.isBlank()) {
                predicates.add(cb.equal(root.get("level"), level));
            }
            if (startDate != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), startDate));
            }
            if (endDate != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), endDate));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        // If the repository didn't extend JpaSpecificationExecutor, we'd have to make sure it does.
        // Wait, DeviceLogRepository currently doesn't extend JpaSpecificationExecutor. I'll add it.
        // I will temporarily return findAllByOrderByCreatedAtDesc until I replace DeviceLogRepository.
        
        return ((org.springframework.data.jpa.repository.JpaSpecificationExecutor<DeviceLog>) logRepository).findAll(spec, pageable);
    }

    @PostMapping
    public ResponseEntity<?> createLog(@RequestBody Map<String, Object> payload,
                                       @RequestHeader(value = "deviceToken", required = false) String deviceToken) {
        Device device = null;
        if (deviceToken != null && !deviceToken.isBlank()) {
            device = deviceRepository.findByRegistrationToken(deviceToken).orElse(null);
        }

        String level = (String) payload.getOrDefault("level", "INFO");
        String event = (String) payload.getOrDefault("event", "Unknown Event");
        String detail = (String) payload.get("detail");

        DeviceLog log = DeviceLog.builder()
                .device(device)
                .level(level)
                .event(event)
                .detail(detail)
                .build();
        
        logRepository.save(log);
        return ResponseEntity.ok().build();
    }
}
