package com.messagingagent.admin;

import com.messagingagent.model.AppUser;
import com.messagingagent.repository.AppUserRepository;
import com.messagingagent.security.JwtService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req) {
        return userRepository.findByUsername(req.getUsername())
                .filter(u -> u.isActive() && passwordEncoder.matches(req.getPassword(), u.getPassword()))
                .map(u -> ResponseEntity.ok(Map.of(
                        "token", jwtService.generateToken(u.getUsername(), u.getRole()),
                        "username", u.getUsername(),
                        "role", u.getRole())))
                .orElse(ResponseEntity.status(401).body(Map.of("error", "Invalid credentials")));
    }

    @Data
    public static class LoginRequest {
        private String username;
        private String password;
    }
}
