package com.messagingagent.smpp;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Thread-safe registry of active SMPP server sessions.
 * Used to send DELIVER_SM / error responses back to upstream clients
 * and to monitor active connections.
 */
@Component
public class SmppSessionRegistry {

    private final Map<String, SmppSessionInfo> sessions = new ConcurrentHashMap<>();

    public void register(String sessionId, SmppSessionInfo sessionInfo) {
        sessions.put(sessionId, sessionInfo);
    }

    public void unregister(String sessionId) {
        sessions.remove(sessionId);
    }

    public Optional<SmppSessionInfo> getSession(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    public Collection<SmppSessionInfo> getAllSessions() {
        return sessions.values();
    }

    public Collection<SmppSessionInfo> getSessionsBySystemId(String systemId) {
        return sessions.values().stream()
                .filter(info -> info.getSession().getConfiguration().getSystemId().equals(systemId))
                .collect(Collectors.toList());
    }

    public int size() {
        return sessions.size();
    }
}
