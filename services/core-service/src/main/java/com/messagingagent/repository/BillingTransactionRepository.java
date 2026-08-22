package com.messagingagent.repository;

import com.messagingagent.model.BillingTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BillingTransactionRepository extends JpaRepository<BillingTransaction, Long> {
    List<BillingTransaction> findByClientIdOrderByCreatedAtDesc(Long clientId);
}
