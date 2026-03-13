package com.messagingagent.repository;

import com.messagingagent.model.SmppClient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SmppClientRepository extends JpaRepository<SmppClient, Long> {
    Optional<SmppClient> findBySystemId(String systemId);
}
