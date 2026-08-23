package com.messagingagent.repository;

import com.messagingagent.model.AccountBilling;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AccountBillingRepository extends JpaRepository<AccountBilling, Long> {
}
