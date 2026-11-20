package com.exe.morphia3d.controller;

import com.exe.morphia3d.dto.response.MyStaffProfileResponse;
import com.exe.morphia3d.security.UserPrincipal;
import com.exe.morphia3d.service.StaffManagementService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
public class CurrentStaffProfileController {
    private final StaffManagementService staffManagementService;

    @GetMapping("/staff-profile")
    public MyStaffProfileResponse get(@AuthenticationPrincipal UserPrincipal principal) {
        return staffManagementService.getCurrent(principal);
    }
}
