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
    List<Device> findByGroup(DeviceGroup group);
    Optional<Device> findByRegistrationToken(String token);
    Optional<Device> findByImei(String imei);
    List<Device> findByStatus(Device.Status status);
}
