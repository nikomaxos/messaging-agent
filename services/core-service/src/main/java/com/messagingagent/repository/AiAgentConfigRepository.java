package com.messagingagent.repository;

import com.messagingagent.model.AiAgentConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AiAgentConfigRepository extends JpaRepository<AiAgentConfig, Long> {
}
