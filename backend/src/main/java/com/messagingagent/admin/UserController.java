package com.messagingagent.admin;

import com.messagingagent.model.AppUser;
import com.messagingagent.repository.AppUserRepository;
import com.messagingagent.security.JwtService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Slf4j
public class UserController {

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    /** List all users (password excluded from response). */
    @GetMapping
    public List<Map<String, Object>> listAll() {
        return userRepository.findAll().stream().map(this::toDto).toList();
    }

    /** Create a new user. */
    @PostMapping
    public ResponseEntity<?> create(@RequestBody @Valid CreateUserRequest req) {
        if (userRepository.findByUsername(req.getUsername()).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username already exists"));
        }
        AppUser user = AppUser.builder()
                .username(req.getUsername())
                .password(passwordEncoder.encode(req.getPassword()))
                .role(req.getRole() != null ? req.getRole() : "ADMIN")
                .active(true)
                .build();
        userRepository.save(user);
        log.info("Created user '{}' with role '{}'", user.getUsername(), user.getRole());
        return ResponseEntity.ok(toDto(user));
    }

    /** Update user details (username, role, active). */
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody @Valid UpdateUserRequest req) {
        return userRepository.findById(id).map(user -> {
            if (req.getUsername() != null && !req.getUsername().isBlank()) {
                // Check uniqueness if username changed
                if (!user.getUsername().equals(req.getUsername()) &&
                    userRepository.findByUsername(req.getUsername()).isPresent()) {
                    return ResponseEntity.badRequest().body((Object) Map.of("error", "Username already taken"));
                }
                user.setUsername(req.getUsername());
            }
            if (req.getRole() != null) user.setRole(req.getRole());
            if (req.getActive() != null) user.setActive(req.getActive());
            userRepository.save(user);
            log.info("Updated user id={} username='{}' role='{}' active={}", id, user.getUsername(), user.getRole(), user.isActive());
            return ResponseEntity.ok((Object) toDto(user));
        }).orElse(ResponseEntity.notFound().build());
    }

    /** Reset a user's password. */
    @PutMapping("/{id}/password")
    public ResponseEntity<?> resetPassword(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String newPassword = body.get("password");
        if (newPassword == null || newPassword.length() < 4) {
            return ResponseEntity.badRequest().body(Map.of("error", "Password must be at least 4 characters"));
        }
        return userRepository.findById(id).map(user -> {
            user.setPassword(passwordEncoder.encode(newPassword));
            userRepository.save(user);
            log.info("Password reset for user id={} username='{}'", id, user.getUsername());
            return ResponseEntity.ok(Map.of("message", "Password updated"));
        }).orElse(ResponseEntity.notFound().build());
    }

    /** Delete a user. Extracts caller from JWT to prevent self-delete. */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id, @RequestHeader("Authorization") String authHeader) {
        return userRepository.findById(id).map(user -> {
            // Prevent self-delete: extract username from JWT
            String token = authHeader.replace("Bearer ", "");
            try {
                var claims = jwtService.validateToken(token);
                String callerUsername = claims != null ? claims.getSubject() : null;
                if (user.getUsername().equals(callerUsername)) {
                    return ResponseEntity.badRequest().body((Object) Map.of("error", "Cannot delete your own account"));
                }
            } catch (Exception ignored) {}
            userRepository.deleteById(id);
            log.info("Deleted user id={} username='{}'", id, user.getUsername());
            return ResponseEntity.ok((Object) Map.of("message", "User deleted"));
        }).orElse(ResponseEntity.notFound().build());
    }

    private Map<String, Object> toDto(AppUser u) {
        var map = new java.util.LinkedHashMap<String, Object>();
        map.put("id", u.getId());
        map.put("username", u.getUsername());
        map.put("role", u.getRole());
        map.put("active", u.isActive());
        map.put("createdAt", u.getCreatedAt() != null ? u.getCreatedAt().toString() : null);
        return map;
    }

    @Data
    public static class CreateUserRequest {
        @NotBlank private String username;
        @NotBlank private String password;
        private String role;
    }

    @Data
    public static class UpdateUserRequest {
        private String username;
        private String role;
        private Boolean active;
    }
}
