package com.messagingagent.repository;

import com.messagingagent.model.AccountTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AccountTransactionRepository extends JpaRepository<AccountTransaction, Long> {
    List<AccountTransaction> findByAccountIdOrderByCreatedAtDesc(Long accountId);
}
