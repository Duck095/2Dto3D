package com.exe.morphia3d.dto.response;

import com.exe.morphia3d.entity.StaffProfile;
import com.exe.morphia3d.enums.StaffStatus;

import java.time.LocalDate;

public record MyStaffProfileResponse(
        Long id,
        String employeeNumber,
        String department,
        String jobTitle,
        StaffStatus status,
        LocalDate joinedAt) {

    public static MyStaffProfileResponse from(StaffProfile profile) {
        return new MyStaffProfileResponse(profile.getId(), profile.getEmployeeNumber(), profile.getDepartment(),
                profile.getJobTitle(), profile.getStatus(), profile.getJoinedAt());
    }
}
