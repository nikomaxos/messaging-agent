package com.messagingagent.repository;

import com.messagingagent.model.Device;
import com.messagingagent.model.DeviceGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DeviceRepository extends JpaRepository<Device, Long> {
    List<Device> findByGroupAndStatus(DeviceGroup group, Device.Status status);
    List<Device> findByStatus(Device.Status status);
    List<Device> findByGroup(DeviceGroup group);
    Optional<Device> findByRegistrationToken(String token);
    Optional<Device> findByImei(String imei);
    long countByStatus(Device.Status status);
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("UPDATE Device d SET d.inFlightDispatches = COALESCE(d.inFlightDispatches, 0) + 1 WHERE d.id = :deviceId")
    void incrementInFlight(@org.springframework.data.repository.query.Param("deviceId") Long deviceId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("UPDATE Device d SET d.inFlightDispatches = CASE WHEN COALESCE(d.inFlightDispatches, 0) > 0 THEN d.inFlightDispatches - 1 ELSE 0 END WHERE d.id = :deviceId")
    void decrementInFlight(@org.springframework.data.repository.query.Param("deviceId") Long deviceId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("UPDATE Device d SET d.inFlightDispatches = 0 WHERE d.id = :deviceId")
    void resetInFlight(@org.springframework.data.repository.query.Param("deviceId") Long deviceId);
}
