package com.exe.morphia3d.repository;

import com.exe.morphia3d.Morphia3dApplication;
import com.exe.morphia3d.entity.StaffProfile;
import com.exe.morphia3d.entity.Task;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.Role;
import com.exe.morphia3d.enums.StaffStatus;
import com.exe.morphia3d.enums.TaskPriority;
import com.exe.morphia3d.enums.TaskStatus;
import com.exe.morphia3d.enums.UserStatus;
import com.exe.morphia3d.security.JwtUtil;
import com.exe.morphia3d.security.UserDetailsServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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

import java.sql.Timestamp;
import java.time.Instant;
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
class TaskRepositoryPostgresRegressionTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("task_list_regression")
            .withUsername("task_list_qa")
            .withPassword("task_list_qa");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        properties.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired StaffProfileRepository profiles;
    @Autowired TaskRepository tasks;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtUtil jwt;
    @Autowired UserDetailsServiceImpl userDetails;

    private MockMvc mvc;
    private User admin;
    private StaffProfile assigneeA;
    private StaffProfile assigneeB;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        admin = user("admin", Role.ADMIN);
        User employeeA = user("employee-a", Role.USER);
        User employeeB = user("employee-b", Role.DESIGNER);
        assigneeA = profile(employeeA);
        assigneeB = profile(employeeB);
    }

    @Test
    void emptyTaskListWithoutFiltersReturnsAnEmptyPostgresPage() throws Exception {
        mvc.perform(get("/api/admin/tasks").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalItems").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    @Test
    void adminTaskListSupportsTypedOptionalFiltersAndAccuratePaginationOnPostgres() throws Exception {
        Task alpha = task("Alpha 100%_\\Case", "Search target", TaskPriority.HIGH, TaskStatus.ASSIGNED,
                assigneeA, Instant.parse("2099-01-01T00:00:00Z"), 4);
        Task beta = task("Beta Alpha", "Ordinary", TaskPriority.NORMAL, TaskStatus.IN_PROGRESS,
                assigneeA, Instant.parse("2000-01-01T00:00:00Z"), 3);
        Task gamma = task("Gamma", "Contains alpha term", TaskPriority.HIGH, TaskStatus.APPROVED,
                assigneeB, Instant.parse("2000-01-01T00:00:00Z"), 2);
        Task delta = task("Delta", "Other", TaskPriority.NORMAL, TaskStatus.REVISION_REQUESTED,
                assigneeB, Instant.parse("2099-01-01T00:00:00Z"), 1);

        // No filters, stable sort, page size, and count accuracy.
        mvc.perform(get("/api/admin/tasks").header("Authorization", bearer(admin)).param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(4))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items[0].id").value(alpha.getId()))
                .andExpect(jsonPath("$.items[1].id").value(beta.getId()));
        mvc.perform(get("/api/admin/tasks").header("Authorization", bearer(admin)).param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(4))
                .andExpect(jsonPath("$.items[0].id").value(gamma.getId()))
                .andExpect(jsonPath("$.items[1].id").value(delta.getId()));

        // q absent and q present both execute as real PostgreSQL requests.
        mvc.perform(get("/api/admin/tasks").header("Authorization", bearer(admin)).param("status", "ASSIGNED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(1));
        mvc.perform(get("/api/admin/tasks").header("Authorization", bearer(admin)).param("q", "alpha"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(3));
        mvc.perform(get("/api/admin/tasks").header("Authorization", bearer(admin)).param("q", "100%_\\Case"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].id").value(alpha.getId()));

        // Assignee, combined search, status and priority filters.
        mvc.perform(get("/api/admin/tasks").header("Authorization", bearer(admin))
                        .param("assigneeStaffProfileId", assigneeA.getId().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(2));
        mvc.perform(get("/api/admin/tasks").header("Authorization", bearer(admin))
                        .param("q", "alpha").param("assigneeStaffProfileId", assigneeA.getId().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(2));
        mvc.perform(get("/api/admin/tasks").header("Authorization", bearer(admin)).param("priority", "HIGH"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(2));
        mvc.perform(get("/api/admin/tasks").header("Authorization", bearer(admin))
                        .param("status", "ASSIGNED").param("priority", "HIGH"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(1));

        // Overdue excludes approved work; due bounds retain inclusive semantics.
        mvc.perform(get("/api/admin/tasks").header("Authorization", bearer(admin)).param("overdue", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].id").value(beta.getId()));
        mvc.perform(get("/api/admin/tasks").header("Authorization", bearer(admin)).param("overdue", "false"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(3));
        mvc.perform(get("/api/admin/tasks").header("Authorization", bearer(admin))
                        .param("dueBefore", "2050-01-01T00:00:00Z"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(2));
        mvc.perform(get("/api/admin/tasks").header("Authorization", bearer(admin))
                        .param("dueAfter", "2050-01-01T00:00:00Z"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(2));
    }

    @Test
    void taskListKeepsAdminAuthenticationAndAuthorizationResponses() throws Exception {
        mvc.perform(get("/api/admin/tasks")).andExpect(status().isUnauthorized());
        User employee = user("unauthorized", Role.USER);
        mvc.perform(get("/api/admin/tasks").header("Authorization", bearer(employee)))
                .andExpect(status().isForbidden());
    }

    private User user(String label, Role role) {
        return users.saveAndFlush(User.builder().name(label).email(UUID.randomUUID() + "@example.invalid")
                .password("qa-only").role(role).status(UserStatus.ACTIVE).build());
    }

    private StaffProfile profile(User user) {
        StaffProfile profile = new StaffProfile();
        profile.setUser(user);
        profile.setEmployeeNumber("QA-" + UUID.randomUUID().toString().substring(0, 10).toUpperCase());
        profile.setDepartment("QA");
        profile.setJobTitle("Regression tester");
        profile.setJoinedAt(LocalDate.of(2026, 1, 1));
        profile.setStatus(StaffStatus.ACTIVE);
        profile.setCreatedBy(admin);
        profile.setUpdatedBy(admin);
        return profiles.saveAndFlush(profile);
    }

    private Task task(String title, String description, TaskPriority priority, TaskStatus status,
                      StaffProfile assignee, Instant dueAt, int order) {
        Task task = new Task();
        task.setTitle(title);
        task.setDescription(description);
        task.setPriority(priority);
        task.setStatus(status);
        task.setAssignee(assignee);
        task.setCreatedBy(admin);
        task.setAssignmentCycle(1);
        task.setDueAt(dueAt);
        Task saved = tasks.saveAndFlush(task);
        jdbc.update("update tasks set updated_at=? where id=?",
                Timestamp.from(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(order)), saved.getId());
        return saved;
    }

    private String bearer(User user) {
        return "Bearer " + jwt.generateToken(userDetails.loadUserByUsername(user.getEmail()));
    }
}
