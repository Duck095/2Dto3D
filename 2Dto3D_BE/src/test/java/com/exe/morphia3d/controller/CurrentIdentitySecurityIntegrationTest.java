package com.exe.morphia3d.controller;

import com.exe.morphia3d.Morphia3dApplication;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.Role;
import com.exe.morphia3d.enums.UserStatus;
import com.exe.morphia3d.repository.UserRepository;
import com.exe.morphia3d.security.JwtUtil;
import com.exe.morphia3d.security.UserPrincipal;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = Morphia3dApplication.class, properties = "app.local-build-id=qa-build-test")
@ActiveProfiles("test")
@Transactional
class CurrentIdentitySecurityIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository users;
    @Autowired private JwtUtil jwtUtil;
    @Value("${jwt.secret}") private String jwtSecret;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void activeAdminGetsCurrentIdentityAndInternalAccessPolicy() throws Exception {
        User admin = saveUser("admin", Role.ADMIN, UserStatus.ACTIVE);

        mockMvc.perform(get("/api/v1/me").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(admin.getId()))
                .andExpect(jsonPath("$.name").value("Identity admin"))
                .andExpect(jsonPath("$.email").value(admin.getEmail()))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.accountStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.staffStatus").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.contexts").isArray())
                .andExpect(jsonPath("$.contexts.length()").value(2))
                .andExpect(jsonPath("$.contexts[0]").value("CUSTOMER"))
                .andExpect(jsonPath("$.contexts[1]").value("ADMIN"))
                .andExpect(jsonPath("$.permissions[0]").value("internal.admin.access"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    @Test
    void activeUserAndDesignerHaveCustomerContextWithoutAdminPermission() throws Exception {
        for (Role role : new Role[]{Role.USER, Role.DESIGNER}) {
            User account = saveUser(role.name().toLowerCase(), role, UserStatus.ACTIVE);

            mockMvc.perform(get("/api/v1/me").header("Authorization", bearer(account)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.role").value(role.name()))
                    .andExpect(jsonPath("$.accountStatus").value("ACTIVE"))
                    .andExpect(jsonPath("$.contexts.length()").value(1))
                    .andExpect(jsonPath("$.contexts[0]").value("CUSTOMER"))
                    .andExpect(jsonPath("$.permissions").isEmpty());
        }
    }

    @Test
    void inactiveAccountCannotUsePreviouslyIssuedUnexpiredToken() throws Exception {
        User account = saveUser("inactive", Role.ADMIN, UserStatus.ACTIVE);
        String token = bearer(account);
        account.setStatus(UserStatus.UNACTIVE);
        users.saveAndFlush(account);

        mockMvc.perform(get("/api/v1/me").header("Authorization", token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredMalformedAndDeletedAccountTokensDoNotAuthenticate() throws Exception {
        User account = saveUser("old-token", Role.ADMIN, UserStatus.ACTIVE);
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + expiredToken(account.getEmail())))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer malformed.token.value"))
                .andExpect(status().isUnauthorized());

        String token = bearer(account);
        users.delete(account);
        users.flush();
        mockMvc.perform(get("/api/v1/me").header("Authorization", token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousMeIsUnauthorizedAndInsufficientRoleIsForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());

        User customer = saveUser("customer", Role.USER, UserStatus.ACTIVE);
        String token = bearer(customer);
        mockMvc.perform(get("/api/v1/me").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("USER"));

        mockMvc.perform(put("/api/accounts/{id}/status", customer.getId())
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("\"UNACTIVE\""))
                .andExpect(status().isForbidden());
    }

    @Test
    void corsAllowsConfiguredTestOriginsAndRejectsUnlistedOrigin() throws Exception {
        mockMvc.perform(get("/api/auth/ping").header("Origin", "http://localhost:3000"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Backend-Build-Id", "qa-build-test"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string("ok"))
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));

        mockMvc.perform(get("/api/auth/ping").header("Origin", "http://127.0.0.1:3000"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://127.0.0.1:3000"));

        mockMvc.perform(get("/api/auth/ping").header("Origin", "http://untrusted.example"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void corsPreflightAllowsAuthorizationForGetAndPost() throws Exception {
        for (String method : new String[]{"GET", "POST"}) {
            mockMvc.perform(options("/api/v1/me")
                            .header("Origin", "http://127.0.0.1:3000")
                            .header("Access-Control-Request-Method", method)
                            .header("Access-Control-Request-Headers", "authorization,content-type"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", "http://127.0.0.1:3000"))
                    .andExpect(header().exists("Access-Control-Allow-Methods"))
                    .andExpect(header().exists("Access-Control-Allow-Headers"));
        }
    }

    @Test
    void corsDoesNotChangeAuthenticationOrAuthorizationStatus() throws Exception {
        mockMvc.perform(get("/api/v1/me").header("Origin", "http://127.0.0.1:3000"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://127.0.0.1:3000"));

        User customer = saveUser("cors-customer", Role.USER, UserStatus.ACTIVE);
        String token = bearer(customer);
        mockMvc.perform(get("/api/v1/me")
                        .header("Origin", "http://127.0.0.1:3000")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://127.0.0.1:3000"));

        mockMvc.perform(put("/api/accounts/{id}/status", customer.getId())
                        .header("Origin", "http://127.0.0.1:3000")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("\"UNACTIVE\""))
                .andExpect(status().isForbidden())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://127.0.0.1:3000"));
    }

    @Test
    void loginRegisterAndPublicCustomerReadRemainAvailable() throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"New Identity\",\"email\":\"new-identity@example.com\",\"phone\":\"123\",\"password\":\"secret1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("new-identity@example.com"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new-identity@example.com\",\"password\":\"secret1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());

        mockMvc.perform(get("/api/materials/")).andExpect(status().isOk());
    }

    private User saveUser(String label, Role role, UserStatus status) {
        return users.saveAndFlush(User.builder().name("Identity " + label)
                .email("identity-" + label + "-" + java.util.UUID.randomUUID() + "@example.com")
                .password("encoded-test-password").role(role).status(status).build());
    }

    private String bearer(User user) {
        return "Bearer " + jwtUtil.generateToken(new UserPrincipal(user));
    }

    private String expiredToken(String subject) {
        Instant now = Instant.now();
        return Jwts.builder().setSubject(subject)
                .setIssuedAt(Date.from(now.minusSeconds(120)))
                .setExpiration(Date.from(now.minusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)), SignatureAlgorithm.HS256)
                .compact();
    }
}
