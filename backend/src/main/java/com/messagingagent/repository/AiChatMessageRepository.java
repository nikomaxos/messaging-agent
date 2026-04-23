package com.messagingagent.repository;

import com.messagingagent.model.AiChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AiChatMessageRepository extends JpaRepository<AiChatMessage, Long> {
    List<AiChatMessage> findAllBySessionIdOrderByCreatedAtAsc(Long sessionId);
    void deleteAllBySessionId(Long sessionId);
}
