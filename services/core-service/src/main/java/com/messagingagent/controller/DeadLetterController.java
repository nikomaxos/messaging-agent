package com.messagingagent.controller;

import com.messagingagent.model.DeadLetterMessage;
import com.messagingagent.service.DeadLetterService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/dlq")
@RequiredArgsConstructor
public class DeadLetterController {

    private final DeadLetterService service;

    @GetMapping
    public Page<DeadLetterMessage> getDeadLetters(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return service.getDeadLetters(page, size);
    }

    @PostMapping("/{id}/retry")
    public DeadLetterMessage retry(@PathVariable Long id) {
        return service.retry(id);
    }

    @DeleteMapping("/{id}")
    public DeadLetterMessage discard(@PathVariable Long id) {
        return service.discard(id);
    }

    @GetMapping("/count")
    public Map<String, Long> countDead() {
        return Map.of("count", service.countDead());
    }
}
