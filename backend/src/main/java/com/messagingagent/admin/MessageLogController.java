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
            @RequestParam(required = false)    String status) {

        PageRequest pageable = PageRequest.of(page, Math.min(size, 200),
                Sort.by(Sort.Direction.DESC, "createdAt"));

        if (status != null && !status.isBlank()) {
            try {
                return logRepository.findByStatus(MessageLog.Status.valueOf(status), pageable);
            } catch (IllegalArgumentException ignored) { /* unknown status → fall through */ }
        }

        return logRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    /** GET /api/logs/{id} */
    @GetMapping("/{id}")
    public ResponseEntity<MessageLog> getById(@PathVariable Long id) {
        return logRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
