package com.messagingagent.admin;

import com.messagingagent.model.MessageLog;
import com.messagingagent.repository.MessageLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST endpoint for the admin panel's Logs page.
 * Returns paginated message log entries ordered by creation time descending.
 */
@RestController
@RequestMapping("/api/logs")
@RequiredArgsConstructor
public class MessageLogController {

    private final MessageLogRepository logRepository;

    /**
     * GET /api/logs?page=0&size=50
     * Returns a paginated list of message logs, newest first.
     */
    @GetMapping
    public Page<MessageLog> list(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false)    String status,
            @RequestParam(required = false)    String senderId,
            @RequestParam(required = false)    String destinationNumber,
            @RequestParam(required = false)    String clientMessageId,
            @RequestParam(required = false)    String supplierMessageId,
            @RequestParam(required = false)    Long deviceId,
            @RequestParam(required = false)    Long deviceGroupId,
            @RequestParam(required = false)    java.time.Instant startDate,
            @RequestParam(required = false)    java.time.Instant endDate) {

        PageRequest pageable = PageRequest.of(page, Math.min(size, 200),
                Sort.by(Sort.Direction.DESC, "createdAt"));

        org.springframework.data.jpa.domain.Specification<MessageLog> spec = (root, query, cb) -> {
            java.util.List<jakarta.persistence.criteria.Predicate> predicates = new java.util.ArrayList<>();
            
            if (status != null && !status.isBlank()) {
                try {
                    predicates.add(cb.equal(root.get("status"), MessageLog.Status.valueOf(status)));
                } catch (IllegalArgumentException ignored) {}
            }
            if (senderId != null && !senderId.isBlank()) {
                predicates.add(cb.equal(root.get("sourceAddress"), senderId));
            }
            if (destinationNumber != null && !destinationNumber.isBlank()) {
                predicates.add(cb.equal(root.get("destinationAddress"), destinationNumber));
            }
            if (clientMessageId != null && !clientMessageId.isBlank()) {
                predicates.add(cb.equal(root.get("customerMessageId"), clientMessageId));
            }
            if (supplierMessageId != null && !supplierMessageId.isBlank()) {
                predicates.add(cb.equal(root.get("supplierMessageId"), supplierMessageId));
            }
            if (deviceId != null) {
                predicates.add(cb.equal(root.get("device").get("id"), deviceId));
            }
            if (deviceGroupId != null) {
                predicates.add(cb.equal(root.get("deviceGroup").get("id"), deviceGroupId));
            }
            if (startDate != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), startDate));
            }
            if (endDate != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), endDate));
            }
            
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };

        return logRepository.findAll(spec, pageable);
    }

    /** GET /api/logs/{id} */
    @GetMapping("/{id}")
    public ResponseEntity<MessageLog> getById(@PathVariable Long id) {
        return logRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
