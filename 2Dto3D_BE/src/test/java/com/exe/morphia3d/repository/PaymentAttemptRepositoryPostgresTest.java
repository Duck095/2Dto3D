package com.exe.morphia3d.repository;

import com.exe.morphia3d.domain.payment.PaymentAttemptStatus;
import com.exe.morphia3d.entity.PaymentAttempt;
import com.exe.morphia3d.enums.PaymentProvider;
import com.exe.morphia3d.test.support.PostgresTestRoleProvisioning;
import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.flyway.schemas=public",
        "spring.flyway.default-schema=public"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class PaymentAttemptRepositoryPostgresTest {
    private static volatile boolean migrationsApplied;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("payment_attempt_repository_test")
            .withUsername("payment_attempt_repository")
            .withPassword("payment_attempt_repository_only");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> {
            migrateIsolatedSchema();
            return POSTGRES.getJdbcUrl();
        });
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        properties.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    private static synchronized void migrateIsolatedSchema() {
        if (migrationsApplied) return;
        JdbcTemplate bootstrap = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        PostgresTestRoleProvisioning.provisionMigrationRoles(bootstrap, POSTGRES.getUsername());
        Flyway throughV14 = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .target(org.flywaydb.core.api.MigrationVersion.fromVersion("14"))
                .load();
        throughV14.migrate();
        PostgresTestRoleProvisioning.grantSnapshotReferencePrivileges(bootstrap);
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .cleanDisabled(true).load().migrate();
        migrationsApplied = true;
    }

    @Autowired
    private PaymentAttemptRepository attempts;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @Test
    void migratedSchemaValidatesAndAttemptQueriesStayOwnerScopedAndLockable() {
        Integer ownerId = insertOwner("owner-a");
        Integer otherOwnerId = insertOwner("owner-b");
        Integer orderId = insertOrder(ownerId, "order-a");
        Integer paymentId = jdbc.queryForObject("""
                INSERT INTO payments(order_id, amount, amount_vnd, method, status)
                VALUES (?, 145000, 145000, 'VNPAY', 'PENDING') RETURNING payment_id
                """, Integer.class, orderId);
        UUID attemptId = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-12T06:00:00Z");
        PaymentAttempt attempt = PaymentAttempt.builder()
                .attemptId(attemptId)
                .paymentId(paymentId)
                .orderId(orderId)
                .ownerId(ownerId)
                .provider(PaymentProvider.VNPAY)
                .amountVnd(145000L)
                .status(PaymentAttemptStatus.PENDING)
                .idempotencyKey("repository-attempt-key")
                .requestFingerprint("order=101;provider=VNPAY;amount=145000")
                .createdAt(now)
                .updatedAt(now)
                .build();

        attempts.saveAndFlush(attempt);
        entityManager.clear();

        assertThat(attempts.findByOwnerIdAndIdempotencyKey(ownerId, "repository-attempt-key"))
                .get().extracting(PaymentAttempt::getAttemptId).isEqualTo(attemptId);
        assertThat(attempts.findByAttemptIdAndOwnerId(attemptId, otherOwnerId)).isEmpty();
        assertThat(attempts.findByOrderIdOrderByCreatedAtDescAttemptIdDesc(orderId))
                .extracting(PaymentAttempt::getAttemptId).containsExactly(attemptId);
        assertThat(attempts.findOrderAttemptsForUpdate(orderId,
                List.of(PaymentAttemptStatus.INITIATED, PaymentAttemptStatus.PENDING, PaymentAttemptStatus.UNKNOWN)))
                .extracting(PaymentAttempt::getAttemptId).containsExactly(attemptId);
        assertThat(attempts.existsByOrderIdAndStatusIn(orderId,
                List.of(PaymentAttemptStatus.INITIATED, PaymentAttemptStatus.PENDING, PaymentAttemptStatus.UNKNOWN))).isTrue();
        assertThat(jdbc.queryForObject("SELECT version::integer FROM public.flyway_schema_history WHERE version = '12' AND success", Integer.class)).isEqualTo(12);
        assertThat(jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = ?", String.class, "public"))
                .contains("payos_payment_links", "payos_webhook_receipts", "payos_reconciliation_queries");
    }

    private Integer insertOwner(String suffix) {
        return jdbc.queryForObject("""
                INSERT INTO users(name, email, password, role, status)
                VALUES (?, ?, 'test-only', 'USER', 'ACTIVE') RETURNING user_id
                """, Integer.class, suffix, "payment-repo-" + suffix + "@example.invalid");
    }

    private Integer insertOrder(Integer ownerId, String suffix) {
        Integer printerId = jdbc.queryForObject("INSERT INTO printers(name, status) VALUES (?, 'ACTIVE') RETURNING printer_id", Integer.class, suffix);
        return jdbc.queryForObject("""
                INSERT INTO orders(user_id, printer_id, total_amount, total_amount_vnd, status)
                VALUES (?, ?, 145000, 145000, 'PENDING') RETURNING order_id
                """, Integer.class, ownerId, printerId);
    }
}
