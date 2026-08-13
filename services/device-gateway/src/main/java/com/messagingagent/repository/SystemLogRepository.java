package com.messagingagent.repository;

import com.messagingagent.model.SystemLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public interface SystemLogRepository extends JpaRepository<SystemLog, Long> {

    @Query("SELECT s FROM SystemLog s WHERE " +
           "(:level IS NULL OR :level = '' OR :level = 'ALL' OR s.level = :level) AND " +
           "(cast(:startTime as timestamp) IS NULL OR s.createdAt >= :startTime) AND " +
           "(cast(:endTime as timestamp) IS NULL OR s.createdAt <= :endTime) " +
           "ORDER BY s.createdAt DESC")
    Page<SystemLog> findWithFilters(
            @Param("level") String level,
            @Param("startTime") Instant startTime,
            @Param("endTime") Instant endTime,
            Pageable pageable);

    @Modifying
    @Query("DELETE FROM SystemLog s WHERE s.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
