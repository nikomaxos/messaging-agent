package com.messagingagent.repository;

import com.messagingagent.model.ScheduledReport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScheduledReportRepository extends JpaRepository<ScheduledReport, Long> {
    Page<ScheduledReport> findAllByOrderByGeneratedAtDesc(Pageable pageable);
}
