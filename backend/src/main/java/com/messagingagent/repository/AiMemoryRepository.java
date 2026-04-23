package com.messagingagent.repository;

import com.messagingagent.model.AiMemory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AiMemoryRepository extends JpaRepository<AiMemory, Long> {
}
