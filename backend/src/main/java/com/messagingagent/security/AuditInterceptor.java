package com.messagingagent.security;

import com.messagingagent.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/**
 * Automatically logs all mutating API calls (POST/PUT/DELETE/PATCH)
 * with the authenticated user, endpoint, and HTTP method.
 */
@Component
@RequiredArgsConstructor
public class AuditInterceptor implements HandlerInterceptor {

    private final AuditService auditService;

    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "DELETE", "PATCH");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String method = request.getMethod();
        if (!MUTATING_METHODS.contains(method)) return true;

        String uri = request.getRequestURI();
        // Skip auth endpoints and heartbeats
        if (uri.startsWith("/api/auth") || uri.contains("/heartbeat") || uri.startsWith("/ws")) return true;

        String username = "anonymous";
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            username = auth.getName();
        }

        String action = switch (method) {
            case "POST" -> "CREATE";
            case "PUT" -> "UPDATE";
            case "DELETE" -> "DELETE";
            case "PATCH" -> "UPDATE";
            default -> method;
        };

        // Extract entity type from URI: /api/devices/5 → "devices"
        String entityType = extractEntityType(uri);
        String entityId = extractEntityId(uri);
        String ip = request.getRemoteAddr();

        auditService.log(username, action, entityType, entityId, method + " " + uri, ip);

        return true;
    }

    private String extractEntityType(String uri) {
        // /api/devices/5 → "devices", /api/ai-agent/config → "ai-agent"
        String[] parts = uri.replace("/api/", "").split("/");
        return parts.length > 0 ? parts[0] : "unknown";
    }

    private String extractEntityId(String uri) {
        String[] parts = uri.split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            if (parts[i].matches("\\d+")) return parts[i];
        }
        return null;
    }
}
