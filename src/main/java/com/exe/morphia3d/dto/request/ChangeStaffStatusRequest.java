package com.exe.morphia3d.dto.request;

import com.exe.morphia3d.enums.StaffStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ChangeStaffStatusRequest(
        @NotNull StaffStatus status,
        @NotBlank @Size(max = 500) String reason) {
}
