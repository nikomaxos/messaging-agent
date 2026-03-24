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
            @RequestParam(required = false)    java.time.Instant endDate,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "DESC")      String sortDir) {

        Sort.Direction direction = "ASC".equalsIgnoreCase(sortDir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        PageRequest pageable = PageRequest.of(page, Math.min(size, 200),
                Sort.by(direction, sortBy));

        org.springframework.data.jpa.domain.Specification<MessageLog> spec = buildSpec(
            status, senderId, destinationNumber, clientMessageId,
            supplierMessageId, deviceId, deviceGroupId, startDate, endDate);

        return logRepository.findAll(spec, pageable);
    }

    /** GET /api/logs/{id} */
    @GetMapping("/{id}")
    public ResponseEntity<MessageLog> getById(@PathVariable Long id) {
        return logRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /api/logs/ids?status=...&senderId=...
     * Returns ALL message IDs matching the given filters (no pagination).
     * Used by the admin panel's "Select All Results" feature.
     */
    @GetMapping("/ids")
    public java.util.List<Long> listIds(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String senderId,
            @RequestParam(required = false) String destinationNumber,
            @RequestParam(required = false) String clientMessageId,
            @RequestParam(required = false) String supplierMessageId,
            @RequestParam(required = false) Long deviceId,
            @RequestParam(required = false) Long deviceGroupId,
            @RequestParam(required = false) java.time.Instant startDate,
            @RequestParam(required = false) java.time.Instant endDate) {

        org.springframework.data.jpa.domain.Specification<MessageLog> spec = buildSpec(
            status, senderId, destinationNumber, clientMessageId,
            supplierMessageId, deviceId, deviceGroupId, startDate, endDate);

        return logRepository.findAll(spec, Sort.by(Sort.Direction.DESC, "id"))
                .stream().map(MessageLog::getId).toList();
    }

    /** Shared filter specification builder */
    private org.springframework.data.jpa.domain.Specification<MessageLog> buildSpec(
            String status, String senderId, String destinationNumber,
            String clientMessageId, String supplierMessageId,
            Long deviceId, Long deviceGroupId,
            java.time.Instant startDate, java.time.Instant endDate) {

        return (root, query, cb) -> {
            java.util.List<jakarta.persistence.criteria.Predicate> predicates = new java.util.ArrayList<>();

            if (status != null && !status.isBlank()) {
                if ("DISPATCHED_TO_RCS".equals(status)) {
                    predicates.add(cb.equal(root.get("status"), MessageLog.Status.DISPATCHED));
                    predicates.add(cb.isNotNull(root.get("rcsSentAt")));
                } else {
                    try {
                        MessageLog.Status statusEnum = MessageLog.Status.valueOf(status);
                        predicates.add(cb.equal(root.get("status"), statusEnum));
                        if (statusEnum == MessageLog.Status.DISPATCHED) {
                            predicates.add(cb.isNull(root.get("rcsSentAt")));
                        }
                    } catch (IllegalArgumentException ignored) {}
                }
            }
            if (senderId != null && !senderId.isBlank()) {
                predicates.add(cb.equal(cb.lower(root.get("sourceAddress")), senderId.trim().toLowerCase()));
            }
            if (destinationNumber != null && !destinationNumber.isBlank()) {
                predicates.add(cb.like(root.get("destinationAddress"), destinationNumber.trim() + "%"));
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
    }
}
