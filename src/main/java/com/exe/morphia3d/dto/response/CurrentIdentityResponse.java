package com.exe.morphia3d.dto.response;

import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.Role;
import com.exe.morphia3d.enums.UserStatus;
import com.exe.morphia3d.security.UserPrincipal;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record CurrentIdentityResponse(
        Integer id,
        String name,
        String email,
        Role role,
        UserStatus accountStatus,
        String staffStatus,
        String[] contexts,
        String[] permissions) {

    public static CurrentIdentityResponse from(UserPrincipal principal) {
        User user = principal.getUser();
        boolean activeAdmin = user.getStatus() == UserStatus.ACTIVE && user.getRole() == Role.ADMIN;
        List<String> contexts = new java.util.ArrayList<>(List.of("CUSTOMER"));
        List<String> permissions = new java.util.ArrayList<>();
        if (activeAdmin) {
            contexts.add("ADMIN");
            permissions.add("internal.admin.access");
        }
        if (!activeAdmin && principal.hasEmployeeAccess()) {
            contexts.add("EMPLOYEE");
            permissions.add("internal.employee.access");
        }
        return new CurrentIdentityResponse(user.getId(), user.getName(), user.getEmail(), user.getRole(),
                user.getStatus(), principal.getStaffStatus(), contexts.toArray(String[]::new),
                permissions.toArray(String[]::new));
    }
}
