package com.exe.morphia3d.controller;

import com.exe.morphia3d.Morphia3dApplication;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.Role;
import com.exe.morphia3d.enums.UserStatus;
import com.exe.morphia3d.repository.UserRepository;
import com.exe.morphia3d.security.JwtUtil;
import com.exe.morphia3d.security.UserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = Morphia3dApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class StaffManagementHttpSecurityIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository users;

    @Autowired
    private JwtUtil jwtUtil;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void realHttpResponsesDistinguishAnonymousUserAndAdminForStaffEndpoints() throws Exception {
        User user = saveUser("http-user", Role.USER);
        User admin = saveUser("http-admin", Role.ADMIN);
        String userToken = bearer(user);
        String adminToken = bearer(admin);

        var identity = send("GET", "/api/v1/me", userToken, null);
        assertThat(identity.statusCode()).isEqualTo(200);
        assertThat(identity.body()).contains("\"role\":\"USER\"").contains("\"CUSTOMER\"");

        var userList = send("GET", "/api/admin/staff", userToken, null);
        assertThat(userList.statusCode()).isEqualTo(403);

        var userCreate = send("POST", "/api/admin/staff", userToken, "{}");
        assertThat(userCreate.statusCode()).isEqualTo(403);

        var invalidTokenList = send("GET", "/api/admin/staff", "Bearer invalid.jwt.value", null);
        assertThat(invalidTokenList.statusCode()).isEqualTo(401);

        var anonymousList = send("GET", "/api/admin/staff", null, null);
        assertThat(anonymousList.statusCode()).isEqualTo(401);

        var adminList = send("GET", "/api/admin/staff", adminToken, null);
        assertThat(adminList.statusCode()).isEqualTo(200);
    }

    private User saveUser(String label, Role role) {
        return users.saveAndFlush(User.builder()
                .name("Staff " + label)
                .email(label + "-" + java.util.UUID.randomUUID() + "@example.com")
                .password("encoded-test-password")
                .role(role)
                .status(UserStatus.ACTIVE)
                .build());
    }

    private String bearer(User user) {
        return "Bearer " + jwtUtil.generateToken(new UserPrincipal(user));
    }

    private HttpResponse<String> send(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (token != null) {
            request.header("Authorization", token);
        }
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
