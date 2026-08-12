package com.messagingagent.repository;

import com.messagingagent.model.DeviceGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DeviceGroupRepository extends JpaRepository<DeviceGroup, Long> {
    List<DeviceGroup> findByActiveTrue();
}
