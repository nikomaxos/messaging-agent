package com.messagingagent.controller;

import com.messagingagent.model.AppUser;
import com.messagingagent.repository.AppUserRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final AppUserRepository userRepository;

    @PutMapping("/theme")
    public ResponseEntity<?> updateThemePreference(@RequestBody ThemeRequest request, Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        return userRepository.findByUsername(authentication.getName())
                .map(user -> {
                    user.setThemePreference(request.getTheme());
                    userRepository.save(user);
                    return ResponseEntity.ok(Map.of("message", "Theme updated", "themePreference", user.getThemePreference()));
                })
                .orElse(ResponseEntity.status(404).body(Map.of("error", "User not found")));
    }

    @Data
    public static class ThemeRequest {
        private String theme;
    }
}
