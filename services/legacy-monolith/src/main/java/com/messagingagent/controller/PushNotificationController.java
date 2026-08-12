package com.messagingagent.controller;

import com.messagingagent.model.AppUser;
import com.messagingagent.model.PushSubscription;
import com.messagingagent.repository.AppUserRepository;
import com.messagingagent.repository.PushSubscriptionRepository;
import com.messagingagent.service.WebPushService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/push")
@RequiredArgsConstructor
@Slf4j
public class PushNotificationController {

    private final WebPushService webPushService;
    private final PushSubscriptionRepository pushSubscriptionRepository;
    private final AppUserRepository appUserRepository;

    @GetMapping("/public-key")
    public ResponseEntity<Map<String, String>> getPublicKey() {
        return ResponseEntity.ok(Map.of("publicKey", webPushService.getPublicKey()));
    }

    @PostMapping("/subscribe")
    public ResponseEntity<Void> subscribe(@RequestBody SubscriptionRequest request) {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        Optional<AppUser> userOpt = appUserRepository.findByUsername(username);
        
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(401).build();
        }
        
        AppUser user = userOpt.get();
        
        Optional<PushSubscription> existing = pushSubscriptionRepository.findByEndpoint(request.getEndpoint());
        if (existing.isEmpty()) {
            PushSubscription sub = PushSubscription.builder()
                .user(user)
                .endpoint(request.getEndpoint())
                .p256dh(request.getKeys().getP256dh())
                .auth(request.getKeys().getAuth())
                .build();
            pushSubscriptionRepository.save(sub);
            log.info("Saved new push subscription for user: {}", username);
        } else {
            PushSubscription sub = existing.get();
            sub.setUser(user);
            sub.setP256dh(request.getKeys().getP256dh());
            sub.setAuth(request.getKeys().getAuth());
            pushSubscriptionRepository.save(sub);
            log.info("Updated push subscription for user: {}", username);
        }
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/unsubscribe")
    public ResponseEntity<Void> unsubscribe(@RequestParam String endpoint) {
        pushSubscriptionRepository.deleteByEndpoint(endpoint);
        return ResponseEntity.ok().build();
    }
}

@Data
class SubscriptionRequest {
    private String endpoint;
    private Keys keys;

    @Data
    public static class Keys {
        private String p256dh;
        private String auth;
    }
}
