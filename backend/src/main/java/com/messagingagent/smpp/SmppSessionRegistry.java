package com.messagingagent.smpp;

import com.cloudhopper.smpp.SmppServerSession;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry of active SMPP server sessions.
 * Used to send DELIVER_SM / error responses back to upstream clients.
 */
@Component
public class SmppSessionRegistry {

    private final Map<String, SmppServerSession> sessions = new ConcurrentHashMap<>();

    public void register(String sessionId, SmppServerSession session) {
        sessions.put(sessionId, session);
    }

    public void unregister(String sessionId) {
        sessions.remove(sessionId);
    }

    public Optional<SmppServerSession> getSession(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    public Collection<SmppServerSession> getAllSessions() {
        return sessions.values();
    }

    public int size() {
        return sessions.size();
    }
}
