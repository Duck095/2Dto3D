package com.exe.morphia3d.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record CreateStaffProfileRequest(
        @NotNull Integer userId,
        @NotBlank @Size(min = 3, max = 32) @Pattern(regexp = "\\s*[A-Za-z0-9-]{3,32}\\s*") String employeeNumber,
        @NotBlank @Size(max = 100) String department,
        @NotBlank @Size(max = 120) String jobTitle,
        @NotNull LocalDate joinedAt) {
}
