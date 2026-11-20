package com.exe.morphia3d.controller;

import com.exe.morphia3d.Morphia3dApplication;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.Role;
import com.exe.morphia3d.enums.UserStatus;
import com.exe.morphia3d.repository.StaffProfileRepository;
import com.exe.morphia3d.repository.UserRepository;
import com.exe.morphia3d.repository.StaffProfileAuditRepository;
import com.exe.morphia3d.security.JwtUtil;
import com.exe.morphia3d.security.UserDetailsServiceImpl;
import com.exe.morphia3d.security.UserPrincipal;
import io.jsonwebtoken.Jwts;
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

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = Morphia3dApplication.class)
@ActiveProfiles("test")
@Transactional
class StaffManagementSecurityIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository users;
    @Autowired private StaffProfileRepository profiles;
    @Autowired private StaffProfileAuditRepository audits;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private UserDetailsServiceImpl userDetailsService;
    @Value("${jwt.secret}") private String jwtSecret;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void adminCanReadEmptyPaginatedStaffDirectory() throws Exception {
        User admin = saveUser("directory-admin", Role.ADMIN);

        mockMvc.perform(get("/api/admin/staff").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    @Test
    void adminCanCreatePendingProfileWithoutChangingUserRole() throws Exception {
        User admin = saveUser("create-admin", Role.ADMIN);
        User employee = saveUser("create-employee", Role.USER);

        mockMvc.perform(post("/api/admin/staff")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":%d,"employeeNumber":" emp-007 ","department":"Design",
                                 "jobTitle":"Technician","joinedAt":"2026-01-12","status":"ACTIVE",
                                 "role":"ADMIN","createdBy":999}
                                """.formatted(employee.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.employeeNumber").value("EMP-007"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.role").value("USER"));

        org.assertj.core.api.Assertions.assertThat(users.findById(employee.getId()).orElseThrow().getRole())
                .isEqualTo(Role.USER);
        org.assertj.core.api.Assertions.assertThat(audits.count()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(audits.findAll().get(0).getNewStatus().name()).isEqualTo("PENDING");
        org.assertj.core.api.Assertions.assertThat(audits.findAll().get(0).getNewDepartment()).isEqualTo("Design");
        org.assertj.core.api.Assertions.assertThat(audits.findAll().get(0).getActor().getId()).isEqualTo(admin.getId());

        long profileId = profiles.findByUser_Id(employee.getId()).orElseThrow().getId();
        mockMvc.perform(get("/api/admin/staff/{id}", profileId).header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employeeNumber").value("EMP-007"))
                .andExpect(jsonPath("$.createdBy").value(admin.getId()));
        mockMvc.perform(get("/api/admin/staff?query=emp-007&status=PENDING&page=0&size=10")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(profileId))
                .andExpect(jsonPath("$.size").value(10));
    }

    @Test
    void sameJwtGetsEmployeeContextAndLosesItAfterStaffStatusIsSuspended() throws Exception {
        User admin = saveUser("lifecycle-admin", Role.ADMIN);
        User employee = saveUser("lifecycle-employee", Role.DESIGNER);
        String adminToken = bearer(admin);
        String employeeToken = bearer(employee);

        long profileId = createPendingProfile(adminToken, employee.getId());
        changeStatus(adminToken, profileId, "ACTIVE", "Verified onboarding");

        mockMvc.perform(get("/api/v1/me").header("Authorization", employeeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("DESIGNER"))
                .andExpect(jsonPath("$.staffStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.contexts").value(org.hamcrest.Matchers.contains("CUSTOMER", "EMPLOYEE")))
                .andExpect(jsonPath("$.permissions").value(org.hamcrest.Matchers.contains("internal.employee.access")));
        mockMvc.perform(get("/api/v1/me/staff-profile").header("Authorization", employeeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.department").value("Design"));

        changeStatus(adminToken, profileId, "SUSPENDED", "Access revoked");

        mockMvc.perform(get("/api/v1/me").header("Authorization", employeeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.staffStatus").value("SUSPENDED"))
                .andExpect(jsonPath("$.contexts").value(org.hamcrest.Matchers.contains("CUSTOMER")))
                .andExpect(jsonPath("$.permissions").isEmpty());
        mockMvc.perform(get("/api/v1/me/staff-profile").header("Authorization", employeeToken))
                .andExpect(status().isForbidden());

        var statusEvents = audits.findAll().stream()
                .filter(event -> event.getAction().name().equals("STATUS_CHANGED")).toList();
        org.assertj.core.api.Assertions.assertThat(statusEvents).hasSize(2);
        org.assertj.core.api.Assertions.assertThat(statusEvents.get(1).getActor().getId()).isEqualTo(admin.getId());
        org.assertj.core.api.Assertions.assertThat(statusEvents.get(1).getReason()).isEqualTo("Access revoked");
    }

    @Test
    void employeeCannotAccessAdminStaffApiAndAnonymousGets401() throws Exception {
        User employee = saveUser("deny-employee", Role.USER);
        User admin = saveUser("deny-admin", Role.ADMIN);
        long profileId = createPendingProfile(bearer(admin), employee.getId());
        changeStatus(bearer(admin), profileId, "ACTIVE", "Verified onboarding");

        mockMvc.perform(get("/api/admin/staff").header("Authorization", bearer(employee)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/staff"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/me/staff-profile"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void corsPreflightAllowsPatchWithAuthorizationHeader() throws Exception {
        mockMvc.perform(options("/api/admin/staff/1/status")
                        .header("Origin", "http://127.0.0.1:3000")
                        .header("Access-Control-Request-Method", "PATCH")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Access-Control-Allow-Origin", "http://127.0.0.1:3000"));
    }

    @Test
    void customerWithoutStaffProfileNeverGetsEmployeeAuthority() throws Exception {
        User customer = saveUser("no-profile", Role.USER);

        mockMvc.perform(get("/api/v1/me").header("Authorization", bearer(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contexts").value(org.hamcrest.Matchers.contains("CUSTOMER")))
                .andExpect(jsonPath("$.permissions").isEmpty())
                .andExpect(jsonPath("$.staffStatus").value(org.hamcrest.Matchers.nullValue()));
        mockMvc.perform(get("/api/v1/me/staff-profile").header("Authorization", bearer(customer)))
                .andExpect(status().isForbidden());
    }

    @Test
    void accountDeactivationRevokesExistingEmployeeJwtWith401() throws Exception {
        User admin = saveUser("deactivate-admin", Role.ADMIN);
        User employee = saveUser("deactivate-employee", Role.USER);
        String employeeToken = bearer(employee);
        long profileId = createPendingProfile(bearer(admin), employee.getId());
        changeStatus(bearer(admin), profileId, "ACTIVE", "Verified onboarding");

        employee.setStatus(UserStatus.UNACTIVE);
        users.saveAndFlush(employee);

        mockMvc.perform(get("/api/v1/me").header("Authorization", employeeToken))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/me/staff-profile").header("Authorization", employeeToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminAccountsCannotBeLinkedAsEmployeeProfiles() throws Exception {
        User admin = saveUser("no-admin-profile-admin", Role.ADMIN);
        User anotherAdmin = saveUser("no-admin-profile-target", Role.ADMIN);

        mockMvc.perform(post("/api/admin/staff")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":%d,"employeeNumber":"ADM-001","department":"Design",
                                 "jobTitle":"Operator","joinedAt":"2026-01-12"}
                                """.formatted(anotherAdmin.getId())))
                .andExpect(status().isConflict());
        org.assertj.core.api.Assertions.assertThat(profiles.count()).isZero();
    }

    @Test
    void employeeAuthorityDoesNotReplaceExistingJwtRoleClaim() throws Exception {
        User admin = saveUser("claim-admin", Role.ADMIN);
        User employee = saveUser("claim-employee", Role.DESIGNER);
        long profileId = createPendingProfile(bearer(admin), employee.getId());
        changeStatus(bearer(admin), profileId, "ACTIVE", "Verified onboarding");

        String jwt = jwtUtil.generateToken(userDetailsService.loadUserByUsername(employee.getEmail()));
        String roleClaim = Jwts.parserBuilder()
                .setSigningKey(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)))
                .build().parseClaimsJws(jwt).getBody().get("role", String.class);

        org.assertj.core.api.Assertions.assertThat(roleClaim).isEqualTo("ROLE_DESIGNER");
    }

    @Test
    void createRejectsDuplicateUserAndNormalizedEmployeeNumber() throws Exception {
        User admin = saveUser("duplicate-admin", Role.ADMIN);
        User employee = saveUser("duplicate-employee", Role.USER);
        User anotherEmployee = saveUser("duplicate-other", Role.DESIGNER);
        String body = """
                {"userId":%d,"employeeNumber":"E-500","department":"Design",
                 "jobTitle":"Technician","joinedAt":"2026-01-12"}
                """;

        mockMvc.perform(post("/api/admin/staff").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(body.formatted(employee.getId())))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/admin/staff").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(body.formatted(employee.getId())))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/admin/staff").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("E-500", "e-500").formatted(anotherEmployee.getId())))
                .andExpect(status().isConflict());
    }

    @Test
    void createValidationAndPageSizeAreRejectedWith400() throws Exception {
        User admin = saveUser("validation-admin", Role.ADMIN);
        User employee = saveUser("validation-employee", Role.USER);

        mockMvc.perform(post("/api/admin/staff")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":%d,"employeeNumber":"!bad!","department":" ",
                                 "jobTitle":"Operator","joinedAt":"2026-01-12"}
                                """.formatted(employee.getId())))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/admin/staff?size=101").header("Authorization", bearer(admin)))
                .andExpect(status().isBadRequest());
        org.assertj.core.api.Assertions.assertThat(profiles.count()).isZero();
    }

    @Test
    void invalidTransitionIsRejectedWithoutWritingAudit() throws Exception {
        User admin = saveUser("transition-admin", Role.ADMIN);
        User employee = saveUser("transition-employee", Role.USER);
        long profileId = createPendingProfile(bearer(admin), employee.getId());

        changeStatusExpecting(bearer(admin), profileId, "SUSPENDED", "Invalid pending transition", 409);

        org.assertj.core.api.Assertions.assertThat(audits.count()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(profiles.findById(profileId).orElseThrow().getStatus().name())
                .isEqualTo("PENDING");
    }

    @Test
    void profilePatchCannotChangeLinkedUserRoleEmployeeNumberOrAuditActor() throws Exception {
        User admin = saveUser("immutable-admin", Role.ADMIN);
        User employee = saveUser("immutable-employee", Role.USER);
        User other = saveUser("immutable-other", Role.DESIGNER);
        long profileId = createPendingProfile(bearer(admin), employee.getId());

        mockMvc.perform(patch("/api/admin/staff/{id}", profileId)
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":%d,"role":"ADMIN","employeeNumber":"OWNED-999",
                                 "createdBy":%d,"updatedBy":%d,"department":"Operations"}
                                """.formatted(other.getId(), other.getId(), other.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(employee.getId()))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.employeeNumber").value("EMP-" + employee.getId()))
                .andExpect(jsonPath("$.department").value("Operations"))
                .andExpect(jsonPath("$.updatedBy").value(admin.getId()));
    }

    private long createPendingProfile(String adminToken, Integer userId) throws Exception {
        mockMvc.perform(post("/api/admin/staff")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":%d,"employeeNumber":"EMP-%d","department":"Design",
                                 "jobTitle":"Technician","joinedAt":"2026-01-12"}
                                """.formatted(userId, userId)))
                .andExpect(status().isCreated());
        return profiles.findByUser_Id(userId).orElseThrow().getId();
    }

    private void changeStatus(String adminToken, long profileId, String statusValue, String reason) throws Exception {
        mockMvc.perform(patch("/api/admin/staff/{id}/status", profileId)
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"%s","reason":"%s"}
                                """.formatted(statusValue, reason)))
                .andExpect(status().isOk());
    }

    private void changeStatusExpecting(String adminToken, long profileId, String statusValue, String reason,
                                       int expectedStatus) throws Exception {
        mockMvc.perform(patch("/api/admin/staff/{id}/status", profileId)
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"%s","reason":"%s"}
                                """.formatted(statusValue, reason)))
                .andExpect(status().is(expectedStatus));
    }

    private User saveUser(String label, Role role) {
        return users.saveAndFlush(User.builder().name("Staff " + label)
                .email("staff-" + label + "-" + java.util.UUID.randomUUID() + "@example.com")
                .password("encoded-test-password").role(role).status(UserStatus.ACTIVE).build());
    }

    private String bearer(User user) {
        return "Bearer " + jwtUtil.generateToken(new UserPrincipal(user));
    }
}
