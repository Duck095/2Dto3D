package com.exe.morphia3d.dto.request;

import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record UpdateStaffProfileRequest(
        @Size(max = 100) String department,
        @Size(max = 120) String jobTitle,
        LocalDate joinedAt) {
}
