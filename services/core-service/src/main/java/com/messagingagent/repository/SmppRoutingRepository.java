package com.messagingagent.repository;

import com.messagingagent.model.SmppClient;
import com.messagingagent.model.SmppRouting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SmppRoutingRepository extends JpaRepository<SmppRouting, Long> {
    Optional<SmppRouting> findBySmppClient(SmppClient smppClient);
    Optional<SmppRouting> findByIsDefaultTrue();
}
