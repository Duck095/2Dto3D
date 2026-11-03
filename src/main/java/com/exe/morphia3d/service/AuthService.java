package com.exe.morphia3d.service;

import com.exe.morphia3d.dto.request.LoginRequest;
import com.exe.morphia3d.dto.request.RegisterRequest;
import com.exe.morphia3d.dto.response.AuthResponse;

public interface AuthService {
    AuthResponse register(RegisterRequest request);
    AuthResponse login(LoginRequest request);
}
