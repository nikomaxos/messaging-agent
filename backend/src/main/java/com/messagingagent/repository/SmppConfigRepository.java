package com.messagingagent.repository;

import com.messagingagent.model.SmppConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SmppConfigRepository extends JpaRepository<SmppConfig, Long> {
    List<SmppConfig> findByActiveTrue();
}
