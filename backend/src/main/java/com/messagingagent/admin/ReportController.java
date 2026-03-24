package com.messagingagent.admin;

import com.messagingagent.model.ScheduledReport;
import com.messagingagent.repository.ScheduledReportRepository;
import com.messagingagent.service.ReportScheduler;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ScheduledReportRepository reportRepository;
    private final ReportScheduler reportScheduler;

    @GetMapping
    public Page<ScheduledReport> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return reportRepository.findAllByOrderByGeneratedAtDesc(PageRequest.of(page, size));
    }

    @GetMapping("/{id}")
    public ScheduledReport get(@PathVariable Long id) {
        return reportRepository.findById(id).orElseThrow();
    }

    /** Manually trigger a report generation */
    @PostMapping("/generate")
    public Map<String, String> generate() {
        reportScheduler.generateDailyReport();
        return Map.of("status", "OK", "message", "Report generated");
    }
}
