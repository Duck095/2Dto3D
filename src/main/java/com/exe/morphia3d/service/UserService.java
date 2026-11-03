package com.exe.morphia3d.service;

import com.exe.morphia3d.enums.Role;
import com.exe.morphia3d.enums.UserStatus;

public interface UserService {
    void updateStatus(Integer id, UserStatus userStatus);
    void updateRole(Integer id, Role role);
}
