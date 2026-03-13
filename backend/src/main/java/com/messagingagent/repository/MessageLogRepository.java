package com.messagingagent.repository;

import com.messagingagent.model.MessageLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MessageLogRepository extends JpaRepository<MessageLog, Long> {
    Optional<MessageLog> findBySmppMessageId(String smppMessageId);
    Page<MessageLog> findAllByOrderByCreatedAtDesc(Pageable pageable);
    Page<MessageLog> findByStatus(MessageLog.Status status, Pageable pageable);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("UPDATE MessageLog m SET m.device = null WHERE m.device.id = :deviceId")
    void clearDeviceReferences(@org.springframework.data.repository.query.Param("deviceId") Long deviceId);
}

