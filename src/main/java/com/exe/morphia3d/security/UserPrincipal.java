package com.exe.morphia3d.security;

import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.entity.StaffProfile;
import com.exe.morphia3d.enums.Role;
import com.exe.morphia3d.enums.StaffStatus;
import com.exe.morphia3d.enums.UserStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

@RequiredArgsConstructor
public class UserPrincipal implements UserDetails {
    private final User user;
    private final StaffProfile staffProfile;

    public UserPrincipal(User user) {
        this(user, null);
    }

    public User getUser() {
        return user;
    }

    public Integer getId() {
        return user.getId();
    }

    public String getStaffStatus() {
        return staffProfile == null ? null : staffProfile.getStatus().name();
    }

    public boolean hasEmployeeAccess() {
        return user.getStatus() == UserStatus.ACTIVE
                && (user.getRole() == Role.USER || user.getRole() == Role.DESIGNER)
                && staffProfile != null
                && staffProfile.getStatus() == StaffStatus.ACTIVE;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        if (hasEmployeeAccess()) {
            return List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()),
                    new SimpleGrantedAuthority("internal.employee.access"));
        }
        return List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
    }

    @Override
    public String getPassword() {
        return user.getPassword();
    }

    @Override
    public String getUsername() {
        return user.getEmail();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return user.getStatus() == UserStatus.ACTIVE;
    }
}
