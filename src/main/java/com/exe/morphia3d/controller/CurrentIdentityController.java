package com.exe.morphia3d.controller;

import com.exe.morphia3d.dto.response.CurrentIdentityResponse;
import com.exe.morphia3d.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CurrentIdentityController {

    @GetMapping("/me")
    public ResponseEntity<CurrentIdentityResponse> me(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(CurrentIdentityResponse.from(principal));
    }
}
