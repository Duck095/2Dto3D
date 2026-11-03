package com.exe.morphia3d.controller;

import com.exe.morphia3d.Morphia3dApplication;
import com.exe.morphia3d.entity.StaffProfile;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.Role;
import com.exe.morphia3d.enums.StaffStatus;
import com.exe.morphia3d.enums.UserStatus;
import com.exe.morphia3d.repository.StaffProfileRepository;
import com.exe.morphia3d.repository.UserRepository;
import com.exe.morphia3d.security.JwtUtil;
import com.exe.morphia3d.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = Morphia3dApplication.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class StaffManagementSearchPostgresIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("staff_search")
            .withUsername("staff_search_test")
            .withPassword("staff_search_test_only");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        // The Testcontainer is isolated; let Hibernate create the mapped test tables without applying or changing migrations.
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        properties.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository users;
    @Autowired private StaffProfileRepository profiles;
    @Autowired private JwtUtil jwtUtil;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void postgresSearchSupportsEmptyQueryEveryFilterCombinationAndRoleChecks() throws Exception {
        User admin = saveUser("admin", Role.ADMIN);
        mockMvc.perform(get("/api/admin/staff").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));
        User alice = saveUser("Alice Search", "alice.search@example.invalid", Role.USER);
        User bob = saveUser("Bob Operator", "bob.operator@example.invalid", Role.DESIGNER);
        saveProfile(admin, alice, "EMP-ALPHA", "Design", StaffStatus.ACTIVE);
        saveProfile(admin, bob, "EMP-BETA", "Operations", StaffStatus.PENDING);
        String adminToken = bearer(admin);

        mockMvc.perform(get("/api/admin/staff").header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get("/api/admin/staff?query=alice").header("Authorization", adminToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].employeeNumber").value("EMP-ALPHA"));
        mockMvc.perform(get("/api/admin/staff?query=alice.search@").header("Authorization", adminToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get("/api/admin/staff?query=beta").header("Authorization", adminToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].employeeNumber").value("EMP-BETA"));
        mockMvc.perform(get("/api/admin/staff?department=design").header("Authorization", adminToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].employeeNumber").value("EMP-ALPHA"));
        mockMvc.perform(get("/api/admin/staff?status=PENDING").header("Authorization", adminToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].employeeNumber").value("EMP-BETA"));
        mockMvc.perform(get("/api/admin/staff?query=bob&department=operations&status=PENDING")
                        .header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].employeeNumber").value("EMP-BETA"));
        mockMvc.perform(get("/api/admin/staff").header("Authorization", bearer(alice)))
                .andExpect(status().isForbidden());
    }

    private User saveUser(String name, Role role) {
        String email = name.toLowerCase().replace(' ', '.') + "." + UUID.randomUUID() + "@example.invalid";
        return saveUser(name, email, role);
    }

    private User saveUser(String name, String email, Role role) {
        return users.saveAndFlush(User.builder().name(name).email(email)
                .password("encoded-test-password").role(role).status(UserStatus.ACTIVE).build());
    }

    private void saveProfile(User admin, User employee, String employeeNumber, String department, StaffStatus status) {
        StaffProfile profile = new StaffProfile();
        profile.setUser(employee);
        profile.setEmployeeNumber(employeeNumber);
        profile.setDepartment(department);
        profile.setJobTitle("Operator");
        profile.setJoinedAt(LocalDate.of(2026, 1, 12));
        profile.setStatus(status);
        profile.setCreatedBy(admin);
        profile.setUpdatedBy(admin);
        profiles.saveAndFlush(profile);
    }

    private String bearer(User user) {
        return "Bearer " + jwtUtil.generateToken(new UserPrincipal(user));
    }
}
