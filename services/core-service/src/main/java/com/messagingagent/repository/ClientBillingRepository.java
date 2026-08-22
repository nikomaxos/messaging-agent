package com.messagingagent.repository;

import com.messagingagent.model.ClientBilling;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ClientBillingRepository extends JpaRepository<ClientBilling, Long> {
}
