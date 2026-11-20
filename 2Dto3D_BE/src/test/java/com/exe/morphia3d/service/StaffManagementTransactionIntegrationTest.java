package com.exe.morphia3d.service;

import com.exe.morphia3d.dto.request.ChangeStaffStatusRequest;
import com.exe.morphia3d.dto.request.CreateStaffProfileRequest;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.Role;
import com.exe.morphia3d.enums.StaffStatus;
import com.exe.morphia3d.enums.UserStatus;
import com.exe.morphia3d.entity.StaffProfile;
import com.exe.morphia3d.repository.StaffProfileAuditRepository;
import com.exe.morphia3d.repository.StaffProfileRepository;
import com.exe.morphia3d.repository.UserRepository;
import com.exe.morphia3d.security.UserPrincipal;
import com.exe.morphia3d.Morphia3dApplication;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = Morphia3dApplication.class)
@ActiveProfiles("test")
class StaffManagementTransactionIntegrationTest {

    @DynamicPropertySource
    static void isolateH2Database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:h2:mem:staff-management-transaction-test;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON");
    }

    @Autowired private UserRepository users;
    @Autowired private StaffProfileRepository profiles;
    @Autowired private StaffProfileAuditRepository audits;
    @Autowired private StaffManagementService staffManagementService;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void jpaDeclaresOnlyTheUserLinkConstraintAndLeavesNormalizedEmployeeNumberToV3Index() throws Exception {
        Table table = StaffProfile.class.getAnnotation(Table.class);
        assertThat(table.uniqueConstraints()).isEmpty();
        JoinColumn userJoin = StaffProfile.class.getDeclaredField("user").getAnnotation(JoinColumn.class);
        assertThat(userJoin.unique()).isTrue();
        assertThat(StaffProfile.class.getDeclaredField("employeeNumber")
                .getAnnotation(jakarta.persistence.Column.class).unique()).isFalse();
    }

    @Test
    void statusChangeAndAuditRollbackTogetherWhenOuterTransactionFails() {
        User admin = saveUser("audit-admin", Role.ADMIN);
        User employee = saveUser("audit-employee", Role.USER);
        var profile = staffManagementService.create(new CreateStaffProfileRequest(employee.getId(),
                        "ROLLBACK-" + employee.getId(), "Design", "Technician", LocalDate.of(2026, 1, 12)),
                new UserPrincipal(admin));
        long initialAuditCount = audits.count();

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            staffManagementService.changeStatus(profile.id(),
                    new ChangeStaffStatusRequest(StaffStatus.ACTIVE, "Approved onboarding"), new UserPrincipal(admin));
            throw new IllegalStateException("force rollback after audit write");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(profiles.findById(profile.id()).orElseThrow().getStatus()).isEqualTo(StaffStatus.PENDING);
        assertThat(audits.count()).isEqualTo(initialAuditCount);
    }

    @Test
    void concurrentCreateRequestsCannotClaimTheSameNormalizedEmployeeNumber() throws Exception {
        jdbc.execute("CREATE UNIQUE INDEX uq_staff_profiles_employee_number_normalized "
                + "ON staff_profiles (employee_number)");
        User admin = saveUser("race-admin", Role.ADMIN);
        User firstEmployee = saveUser("race-first", Role.USER);
        User secondEmployee = saveUser("race-second", Role.DESIGNER);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<CreateOutcome> first = executor.submit(() -> tryCreateProfile(
                    admin, firstEmployee, ready, start));
            Future<CreateOutcome> second = executor.submit(() -> tryCreateProfile(
                    admin, secondEmployee, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var outcomes = java.util.List.of(first.get(), second.get());
            assertThat(outcomes).filteredOn(outcome -> outcome == CreateOutcome.CREATED).hasSize(1);
            assertThat(outcomes).filteredOn(outcome -> outcome != CreateOutcome.CREATED)
                    .singleElement()
                    .isIn(CreateOutcome.SERVICE_LEVEL_EMPLOYEE_NUMBER_CONFLICT,
                            CreateOutcome.DATABASE_UNIQUE_EMPLOYEE_NUMBER_CONFLICT);
        } finally {
            executor.shutdownNow();
        }
        assertThat(profiles.existsByEmployeeNumberIgnoreCase("RACE-900")).isTrue();
        assertThat(profiles.findAll().stream().filter(profile -> profile.getEmployeeNumber().equals("RACE-900")).count())
                .isEqualTo(1);
    }

    private CreateOutcome tryCreateProfile(User admin, User employee, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            staffManagementService.create(new CreateStaffProfileRequest(employee.getId(), "race-900",
                            "Design", "Technician", LocalDate.of(2026, 1, 12)), new UserPrincipal(admin));
            return CreateOutcome.CREATED;
        } catch (ResponseStatusException failure) {
            if (failure.getStatusCode().value() == 409
                    && "Employee number is already in use".equals(failure.getReason())) {
                return CreateOutcome.SERVICE_LEVEL_EMPLOYEE_NUMBER_CONFLICT;
            }
            throw failure;
        } catch (DataIntegrityViolationException failure) {
            if (isExpectedEmployeeNumberConflict(failure)) {
                return CreateOutcome.DATABASE_UNIQUE_EMPLOYEE_NUMBER_CONFLICT;
            }
            throw failure;
        }
    }

    private boolean isExpectedEmployeeNumberConflict(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException violation
                    && "23505".equals(violation.getSQLState())
                    && "uq_staff_profiles_employee_number_normalized".equalsIgnoreCase(violation.getConstraintName())) {
                return true;
            }
            if (cause instanceof java.sql.SQLException sqlException
                    && "23505".equals(sqlException.getSQLState())
                    && sqlException.getMessage() != null
                    && sqlException.getMessage().toLowerCase(java.util.Locale.ROOT)
                            .contains("uq_staff_profiles_employee_number_normalized")) {
                return true;
            }
        }
        return false;
    }

    private enum CreateOutcome {
        CREATED,
        SERVICE_LEVEL_EMPLOYEE_NUMBER_CONFLICT,
        DATABASE_UNIQUE_EMPLOYEE_NUMBER_CONFLICT
    }

    private User saveUser(String label, Role role) {
        String suffix = UUID.randomUUID().toString();
        return users.saveAndFlush(User.builder().name("Staff " + label)
                .email("staff-" + label + "-" + suffix + "@example.com")
                .password("encoded-test-password").role(role).status(UserStatus.ACTIVE).build());
    }
}
