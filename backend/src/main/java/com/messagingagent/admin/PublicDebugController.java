package com.messagingagent.admin;

import com.messagingagent.model.MessageLog;
import com.messagingagent.repository.MessageLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class PublicDebugController {

    private final MessageLogRepository logRepository;

    @GetMapping("/debug/msg")
    public MessageLog getMsg(@RequestParam String clientMessageId) {
        return logRepository.findBySmppMessageId(clientMessageId).orElse(null);
    }
}
