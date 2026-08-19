package com.messagingagent.controller;

import com.messagingagent.model.ScheduledReport;
import com.messagingagent.repository.ScheduledReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ScheduledReportRepository repository;

    @GetMapping
    public Page<ScheduledReport> getReports(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return repository.findAll(PageRequest.of(page, size));
    }

    @PostMapping("/generate")
    public Map<String, String> generateReport() {
        return Map.of("status", "Generation started successfully");
    }
}
