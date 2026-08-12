package com.messagingagent.admin;

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

    private final DeadLetterService deadLetterService;

    @GetMapping
    public Page<DeadLetterMessage> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return deadLetterService.getDeadLetters(page, size);
    }

    @GetMapping("/count")
    public Map<String, Long> count() {
        return Map.of("count", deadLetterService.countDead());
    }

    @PostMapping("/{id}/retry")
    public DeadLetterMessage retry(@PathVariable Long id) {
        return deadLetterService.retry(id);
    }

    @DeleteMapping("/{id}")
    public DeadLetterMessage discard(@PathVariable Long id) {
        return deadLetterService.discard(id);
    }
}
