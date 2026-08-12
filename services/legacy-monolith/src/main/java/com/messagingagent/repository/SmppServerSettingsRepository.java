package com.messagingagent.repository;

import com.messagingagent.model.SmppServerSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SmppServerSettingsRepository extends JpaRepository<SmppServerSettings, Long> {
}
