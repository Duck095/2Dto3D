package com.exe.morphia3d.dto.response;

import com.exe.morphia3d.entity.StaffProfile;
import com.exe.morphia3d.enums.Role;
import com.exe.morphia3d.enums.UserStatus;
import com.exe.morphia3d.enums.StaffStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record StaffProfileResponse(
        Long id,
        Integer userId,
        String name,
        String email,
        Role role,
        UserStatus accountStatus,
        String employeeNumber,
        String department,
        String jobTitle,
        StaffStatus status,
        LocalDate joinedAt,
        Integer createdBy,
        LocalDateTime createdAt,
        Integer updatedBy,
        LocalDateTime updatedAt) {

    public static StaffProfileResponse from(StaffProfile profile) {
        return new StaffProfileResponse(profile.getId(), profile.getUser().getId(), profile.getUser().getName(),
                profile.getUser().getEmail(), profile.getUser().getRole(), profile.getUser().getStatus(),
                profile.getEmployeeNumber(), profile.getDepartment(), profile.getJobTitle(), profile.getStatus(),
                profile.getJoinedAt(), profile.getCreatedBy().getId(), profile.getCreatedAt(),
                profile.getUpdatedBy().getId(), profile.getUpdatedAt());
    }
}
