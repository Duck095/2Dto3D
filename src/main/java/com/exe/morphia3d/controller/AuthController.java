package com.exe.morphia3d.controller;

import com.exe.morphia3d.dto.request.LoginRequest;
import com.exe.morphia3d.dto.request.RegisterRequest;
import com.exe.morphia3d.dto.response.AuthResponse;
import com.exe.morphia3d.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;

    @Value("${app.local-build-id:}")
    private String localBuildId;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.ok(authService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @GetMapping("/ping")
    public ResponseEntity<String> ping() {
        if (localBuildId == null || localBuildId.isBlank()) {
            return ResponseEntity.ok("ok");
        }
        return ResponseEntity.ok().header("X-Backend-Build-Id", localBuildId).body("ok");
    }
}
