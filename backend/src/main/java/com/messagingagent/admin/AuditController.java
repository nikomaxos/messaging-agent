package com.messagingagent.admin;

import com.messagingagent.model.AuditLog;
import com.messagingagent.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditService auditService;

    @GetMapping
    public Page<AuditLog> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String action) {
        return auditService.list(page, size, username, action);
    }
}
