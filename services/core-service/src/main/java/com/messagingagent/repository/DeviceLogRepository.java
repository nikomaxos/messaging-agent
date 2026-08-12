package com.messagingagent.repository;

import com.messagingagent.model.DeviceLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface DeviceLogRepository extends JpaRepository<DeviceLog, Integer>, JpaSpecificationExecutor<DeviceLog> {
    Page<DeviceLog> findAllByOrderByCreatedAtDesc(Pageable pageable);
    void deleteByDeviceId(Long deviceId);
}
