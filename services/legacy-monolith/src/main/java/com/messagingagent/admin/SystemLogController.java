package com.messagingagent.admin;

import com.messagingagent.model.SystemLog;
import com.messagingagent.repository.SystemLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController
@RequestMapping("/api/admin/system-logs")
@RequiredArgsConstructor
public class SystemLogController {

    private final SystemLogRepository systemLogRepository;

    @GetMapping
    public Page<SystemLog> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) Instant startTime,
            @RequestParam(required = false) Instant endTime) {
        
        return systemLogRepository.findWithFilters(level, startTime, endTime, PageRequest.of(page, size));
    }
}
