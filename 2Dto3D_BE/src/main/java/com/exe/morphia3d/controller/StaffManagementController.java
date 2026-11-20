package com.exe.morphia3d.controller;

import com.exe.morphia3d.dto.request.ChangeStaffStatusRequest;
import com.exe.morphia3d.dto.request.CreateStaffProfileRequest;
import com.exe.morphia3d.dto.request.UpdateStaffProfileRequest;
import com.exe.morphia3d.dto.response.StaffPageResponse;
import com.exe.morphia3d.dto.response.StaffProfileResponse;
import com.exe.morphia3d.enums.StaffStatus;
import com.exe.morphia3d.security.UserPrincipal;
import com.exe.morphia3d.service.StaffManagementService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/staff")
@RequiredArgsConstructor
@Validated
public class StaffManagementController {
    private final StaffManagementService staffManagementService;

    @GetMapping
    public StaffPageResponse list(@RequestParam(required = false) String query,
                                  @RequestParam(required = false) StaffStatus status,
                                  @RequestParam(required = false) String department,
                                  @RequestParam(defaultValue = "0") @Min(0) int page,
                                  @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return staffManagementService.list(query, status, department, page, size);
    }

    @PostMapping
    public ResponseEntity<StaffProfileResponse> create(@Valid @RequestBody CreateStaffProfileRequest request,
                                                        @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(staffManagementService.create(request, principal));
    }

    @GetMapping("/{id}")
    public StaffProfileResponse get(@PathVariable @Min(1) long id) {
        return staffManagementService.get(id);
    }

    @PatchMapping("/{id}")
    public StaffProfileResponse update(@PathVariable @Min(1) long id,
                                       @Valid @RequestBody UpdateStaffProfileRequest request,
                                       @AuthenticationPrincipal UserPrincipal principal) {
        return staffManagementService.update(id, request, principal);
    }

    @PatchMapping("/{id}/status")
    public StaffProfileResponse changeStatus(@PathVariable @Min(1) long id,
                                             @Valid @RequestBody ChangeStaffStatusRequest request,
                                             @AuthenticationPrincipal UserPrincipal principal) {
        return staffManagementService.changeStatus(id, request, principal);
    }
}
