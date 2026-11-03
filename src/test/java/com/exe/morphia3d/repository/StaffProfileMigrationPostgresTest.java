package com.exe.morphia3d.repository;

import com.exe.morphia3d.entity.StaffProfile;
import com.exe.morphia3d.entity.StaffProfileAudit;
import com.exe.morphia3d.entity.User;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class StaffProfileMigrationPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("staff_migration")
            .withUsername("migration_test")
            .withPassword("migration_test_only");

    @Test
    void v3IsAdditiveAndDatabaseConstraintsProtectConcurrentStaffCreation() throws Exception {
        DataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        Flyway throughV1 = flyway(dataSource).target(MigrationVersion.fromVersion("1")).load();
        assertThat(throughV1.migrate().migrationsExecuted).isEqualTo(1);
        Integer existingUserId = jdbc.queryForObject("""
                INSERT INTO users (name, email, password, role, status)
                VALUES ('Legacy Employee Candidate', 'legacy-staff@example.invalid', 'test-hash', 'USER', 'ACTIVE')
                RETURNING user_id
                """, Integer.class);

        Flyway throughV3 = flyway(dataSource).load();
        assertThat(throughV3.migrate().migrationsExecuted).isEqualTo(2);
        throughV3.validate();
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history ORDER BY installed_rank", String.class))
                .containsExactly("1", "2", "3");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE user_id = ?", Integer.class, existingUserId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM staff_profiles", Integer.class)).isZero();
        validateStaffMappings(dataSource);

        Integer adminId = insertUser(jdbc, "staff-admin", "ADMIN");
        Integer employeeOneId = insertUser(jdbc, "staff-one", "USER");
        Integer employeeTwoId = insertUser(jdbc, "staff-two", "DESIGNER");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO staff_profiles
                    (user_id, employee_number, department, job_title, status, joined_at, created_by, updated_by)
                VALUES (-987654, 'EMP-FK-1', 'Design', 'Technician', 'PENDING', DATE '2026-01-12', ?, ?)
                """, adminId, adminId))
                .isInstanceOf(DataAccessException.class)
                .satisfies(failure -> assertThat(hasPostgresConstraintViolation(
                        failure, "23503", "fk_staff_profiles_user")).isTrue());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> tryInsertProfile(jdbc, employeeOneId, adminId, ready, start));
            Future<Boolean> second = executor.submit(() -> tryInsertProfile(jdbc, employeeTwoId, adminId, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(), second.get()).stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM staff_profiles WHERE employee_number = 'EMP-900'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM pg_indexes
                WHERE schemaname = 'public' AND indexname = 'uq_staff_profiles_employee_number_normalized'
                """, Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO staff_profiles
                    (user_id, employee_number, department, job_title, status, joined_at, created_by, updated_by)
                VALUES (?, 'emp-900', 'Design', 'Technician', 'PENDING', DATE '2026-01-12', ?, ?)
                """, insertUser(jdbc, "case-collision", "USER"), adminId, adminId))
                .isInstanceOf(DataAccessException.class)
                .satisfies(failure -> assertThat(hasPostgresConstraintViolation(
                        failure, "23514", "ck_staff_profiles_employee_number")).isTrue());
    }

    private boolean tryInsertProfile(JdbcTemplate jdbc, int employeeId, int adminId,
                                     CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            jdbc.update("""
                    INSERT INTO staff_profiles
                        (user_id, employee_number, department, job_title, status, joined_at, created_by, updated_by)
                    VALUES (?, 'EMP-900', 'Design', 'Technician', 'PENDING', DATE '2026-01-12', ?, ?)
                    """, employeeId, adminId, adminId);
            return true;
        } catch (DataAccessException failure) {
            if (isExpectedEmployeeNumberConflict(failure)) {
                return false;
            }
            throw failure;
        }
    }

    private boolean isExpectedEmployeeNumberConflict(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.postgresql.util.PSQLException postgresException
                    && "23505".equals(postgresException.getSQLState())
                    && postgresException.getServerErrorMessage() != null
                    && "uq_staff_profiles_employee_number_normalized".equals(
                            postgresException.getServerErrorMessage().getConstraint())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasPostgresConstraintViolation(Throwable failure, String sqlState, String constraintName) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.postgresql.util.PSQLException postgresException
                    && sqlState.equals(postgresException.getSQLState())
                    && postgresException.getServerErrorMessage() != null
                    && constraintName.equals(postgresException.getServerErrorMessage().getConstraint())) {
                return true;
            }
        }
        return false;
    }

    private void validateStaffMappings(DataSource dataSource) {
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.datasource", dataSource)
                .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                .applySetting("hibernate.hbm2ddl.auto", "validate")
                .build();
        try (SessionFactory sessionFactory = new MetadataSources(registry)
                .addAnnotatedClass(User.class)
                .addAnnotatedClass(StaffProfile.class)
                .addAnnotatedClass(StaffProfileAudit.class)
                .buildMetadata()
                .buildSessionFactory()) {
            assertThat(sessionFactory.getMetamodel().getEntities()).hasSize(3);
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    private Integer insertUser(JdbcTemplate jdbc, String label, String role) {
        return jdbc.queryForObject("""
                INSERT INTO users (name, email, password, role, status)
                VALUES (?, ?, 'test-hash', ?, 'ACTIVE') RETURNING user_id
                """, Integer.class, label, label + "@example.invalid", role);
    }

    private org.flywaydb.core.api.configuration.FluentConfiguration flyway(DataSource dataSource) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration");
    }
}
