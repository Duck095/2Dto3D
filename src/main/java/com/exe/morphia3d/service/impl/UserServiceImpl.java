package com.exe.morphia3d.service.impl;

import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.Role;
import com.exe.morphia3d.enums.UserStatus;
import com.exe.morphia3d.repository.UserRepository;
import com.exe.morphia3d.service.UserService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {
    private final UserRepository userRepository;

    @Override
    public void updateStatus(Integer id, UserStatus userStatus) {
        User user = findUser(id);
        user.setStatus(userStatus);
        userRepository.save(user);
    }

    @Override
    public void updateRole(Integer id, Role role) {
        User user = findUser(id);
        user.setRole(role);
        userRepository.save(user);
    }

    private User findUser(Integer id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("User không tồn tại: " + id));
    }
}
