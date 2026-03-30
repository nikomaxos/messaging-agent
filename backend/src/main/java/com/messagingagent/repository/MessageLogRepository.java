package com.messagingagent.repository;

import com.messagingagent.model.MessageLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MessageLogRepository extends JpaRepository<MessageLog, Long>, JpaSpecificationExecutor<MessageLog> {
    Optional<MessageLog> findBySmppMessageId(String smppMessageId);
    Optional<MessageLog> findBySupplierMessageId(String supplierMessageId);
    Page<MessageLog> findAllByOrderByCreatedAtDesc(Pageable pageable);
    Page<MessageLog> findByStatus(MessageLog.Status status, Pageable pageable);

    @org.springframework.data.jpa.repository.Query("SELECT m FROM MessageLog m WHERE m.status = :status AND m.deviceGroup.id = :groupId ORDER BY m.createdAt ASC LIMIT 1")
    Optional<MessageLog> findFirstByStatusAndDeviceGroupIdOrderByCreatedAtAsc(
            @org.springframework.data.repository.query.Param("status") MessageLog.Status status,
            @org.springframework.data.repository.query.Param("groupId") Long groupId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("UPDATE MessageLog m SET m.device = null WHERE m.device.id = :deviceId")
    void clearDeviceReferences(@org.springframework.data.repository.query.Param("deviceId") Long deviceId);

    @org.springframework.data.jpa.repository.Query("SELECT COUNT(m) FROM MessageLog m WHERE m.createdAt >= :startDate")
    long countTotalSince(@org.springframework.data.repository.query.Param("startDate") java.time.Instant startDate);

    @org.springframework.data.jpa.repository.Query("SELECT COUNT(m) FROM MessageLog m WHERE m.status IN :statuses AND m.createdAt >= :startDate")
    long countByStatusesSince(@org.springframework.data.repository.query.Param("statuses") java.util.List<MessageLog.Status> statuses, @org.springframework.data.repository.query.Param("startDate") java.time.Instant startDate);

    @org.springframework.data.jpa.repository.Query("SELECT COUNT(m) FROM MessageLog m WHERE (m.smscSupplier.id = :supplierId OR m.fallbackSmsc.id = :supplierId) AND m.createdAt >= :startDate")
    long countTotalBySmscSince(@org.springframework.data.repository.query.Param("supplierId") Long supplierId, @org.springframework.data.repository.query.Param("startDate") java.time.Instant startDate);

    @org.springframework.data.jpa.repository.Query("SELECT COUNT(m) FROM MessageLog m WHERE (m.smscSupplier.id = :supplierId OR m.fallbackSmsc.id = :supplierId) AND m.status IN :statuses AND m.createdAt >= :startDate")
    long countBySmscAndStatusesSince(@org.springframework.data.repository.query.Param("supplierId") Long supplierId, @org.springframework.data.repository.query.Param("statuses") java.util.List<MessageLog.Status> statuses, @org.springframework.data.repository.query.Param("startDate") java.time.Instant startDate);

    @org.springframework.data.jpa.repository.Query("SELECT m FROM MessageLog m WHERE m.status = :status AND m.rcsExpiresAt < :now")
    java.util.List<MessageLog> findExpiredLogs(@org.springframework.data.repository.query.Param("status") MessageLog.Status status, @org.springframework.data.repository.query.Param("now") java.time.Instant now);

    java.util.List<MessageLog> findByStatusAndRcsSentAtIsNotNull(MessageLog.Status status);

    java.util.List<MessageLog> findByStatusAndRcsSentAtIsNullAndDispatchedAtBefore(MessageLog.Status status, java.time.Instant threshold);

    @org.springframework.data.jpa.repository.Query("SELECT m FROM MessageLog m WHERE m.status = 'DISPATCHED' AND m.dispatchedAt < :cutoff AND m.fallbackStartedAt IS NULL")
    java.util.List<MessageLog> findStaleDispatched(@org.springframework.data.repository.query.Param("cutoff") java.time.Instant cutoff);

    // --- Performance scoring queries (rolling 2h window, per device) ---

    @org.springframework.data.jpa.repository.Query(
        value = "SELECT COALESCE(AVG(EXTRACT(EPOCH FROM (rcs_dlr_received_at - dispatched_at))), 0) " +
                "FROM message_log WHERE device_id = :deviceId AND status = 'DELIVERED' " +
                "AND dispatched_at >= :since AND rcs_dlr_received_at IS NOT NULL",
        nativeQuery = true)
    double avgDeliveryLatencySeconds(
            @org.springframework.data.repository.query.Param("deviceId") Long deviceId,
            @org.springframework.data.repository.query.Param("since") java.time.Instant since);

    @org.springframework.data.jpa.repository.Query(
        value = "SELECT COUNT(*) FROM message_log WHERE device_id = :deviceId AND status = 'DELIVERED' AND dispatched_at >= :since",
        nativeQuery = true)
    long countDeliveredByDevice(
            @org.springframework.data.repository.query.Param("deviceId") Long deviceId,
            @org.springframework.data.repository.query.Param("since") java.time.Instant since);

    @org.springframework.data.jpa.repository.Query(
        value = "SELECT COUNT(*) FROM message_log WHERE device_id = :deviceId AND status = 'FAILED' AND dispatched_at >= :since",
        nativeQuery = true)
    long countFailedByDevice(
            @org.springframework.data.repository.query.Param("deviceId") Long deviceId,
            @org.springframework.data.repository.query.Param("since") java.time.Instant since);

    @org.springframework.data.jpa.repository.Query(
        value = "SELECT COUNT(*) FROM message_log WHERE device_id = :deviceId AND status IN ('DELIVERED','FAILED','RCS_FAILED','DISPATCHED') AND dispatched_at >= :since",
        nativeQuery = true)
    long countDispatchedByDevice(
            @org.springframework.data.repository.query.Param("deviceId") Long deviceId,
            @org.springframework.data.repository.query.Param("since") java.time.Instant since);

    long countByStatusAndCreatedAtAfter(MessageLog.Status status, java.time.Instant after);

    @org.springframework.data.jpa.repository.Query("SELECT COUNT(m) FROM MessageLog m WHERE m.status = :status AND m.createdAt >= :after AND (m.sourceAddress IS NULL OR m.sourceAddress != 'SYSTEM')")
    long countUserMessagesByStatusAndCreatedAtAfter(
            @org.springframework.data.repository.query.Param("status") MessageLog.Status status,
            @org.springframework.data.repository.query.Param("after") java.time.Instant after);

    // DLQ: find failed messages older than cutoff
    java.util.List<MessageLog> findByStatusAndCreatedAtBefore(MessageLog.Status status, java.time.Instant before);

    // Throughput: per-SMSC and per-device counts for rate dashboard
    long countBySmscSupplierIdAndCreatedAtAfter(Long supplierId, java.time.Instant after);
    long countByDeviceIdAndCreatedAtAfter(Long deviceId, java.time.Instant after);

    // Live TPS: count messages in time buckets
    @org.springframework.data.jpa.repository.Query(
        value = "SELECT date_trunc('second', COALESCE(rcs_dlr_received_at, delivered_at, fallback_dlr_received_at, created_at)) AS ts, COUNT(*) AS cnt " +
                "FROM message_log WHERE status IN ('DELIVERED', 'FAILED', 'RCS_FAILED') AND COALESCE(rcs_dlr_received_at, delivered_at, fallback_dlr_received_at, created_at) >= :since " +
                "GROUP BY ts ORDER BY ts",
        nativeQuery = true)
    java.util.List<Object[]> countPerSecondSince(
            @org.springframework.data.repository.query.Param("since") java.sql.Timestamp since);

    long countByCreatedAtAfter(java.time.Instant after);
    
    @org.springframework.data.jpa.repository.Query(
        "SELECT COUNT(m) FROM MessageLog m WHERE m.status IN ('DELIVERED', 'FAILED', 'RCS_FAILED') " +
        "AND COALESCE(m.rcsDlrReceivedAt, m.deliveredAt, m.fallbackDlrReceivedAt, m.createdAt) >= :since")
    long countTerminalAfter(@org.springframework.data.repository.query.Param("since") java.time.Instant since);
}
