package com.messagingagent.repository;

import com.messagingagent.model.NotificationAlert;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;

@Repository
public interface NotificationAlertRepository extends JpaRepository<NotificationAlert, Long> {
    Page<NotificationAlert> findAllByOrderByCreatedAtDesc(Pageable pageable);
    
    Page<NotificationAlert> findAllByAcknowledgedOrderByCreatedAtDesc(boolean acknowledged, Pageable pageable);
    
    @Modifying
    @Query("UPDATE NotificationAlert a SET a.acknowledged = true WHERE a.acknowledged = false")
    int acknowledgeAll();
    
    @Modifying
    @Query("DELETE FROM NotificationAlert a WHERE a.createdAt < :cutoffDate")
    int deleteOlderThan(@Param("cutoffDate") Instant cutoffDate);
}
