package com.messagingagent.service;

import com.messagingagent.model.AuditLog;
import com.messagingagent.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    public void log(String username, String action, String entityType, String entityId, String details, String ipAddress) {
        AuditLog entry = AuditLog.builder()
                .username(username)
                .action(action)
                .entityType(entityType)
                .entityId(entityId)
                .details(details)
                .ipAddress(ipAddress)
                .build();
        auditLogRepository.save(entry);
        log.debug("Audit: {} {} {} #{} - {}", username, action, entityType, entityId, details);
    }

    public Page<AuditLog> list(int page, int size, String username, String action) {
        if (username != null && !username.isBlank()) {
            return auditLogRepository.findByUsernameOrderByCreatedAtDesc(username, PageRequest.of(page, size));
        }
        if (action != null && !action.isBlank()) {
            return auditLogRepository.findByActionOrderByCreatedAtDesc(action, PageRequest.of(page, size));
        }
        return auditLogRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size));
    }
}
