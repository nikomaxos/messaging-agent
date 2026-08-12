package com.messagingagent.repository;

import com.messagingagent.model.DeadLetterMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeadLetterRepository extends JpaRepository<DeadLetterMessage, Long> {

    Page<DeadLetterMessage> findByStatusOrderByCreatedAtDesc(
            DeadLetterMessage.DlqStatus status, Pageable pageable);

    long countByStatus(DeadLetterMessage.DlqStatus status);
}
