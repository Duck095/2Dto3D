package com.exe.morphia3d.repository;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class PaymentAttemptMigrationPostgresTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("payment_attempt_migration_test")
            .withUsername("payment_attempt_test")
            .withPassword("payment_attempt_test_only");

    @Test
    void freshInstallAppliesPaymentV11AfterPinnedAiV10AndCreatesRequiredIndexes() {
        DataSource dataSource = dataSource("payment_fresh_schema");
        Flyway throughV10 = flyway(dataSource, "payment_fresh_schema", "10");
        assertThat(throughV10.migrate().migrationsExecuted).isEqualTo(10);
        throughV10.validate();

        Flyway throughV11 = flyway(dataSource, "payment_fresh_schema", "11");
        assertThat(throughV11.migrate().migrationsExecuted).isEqualTo(1);
        throughV11.validate();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT MAX(version::integer) FROM flyway_schema_history WHERE success", Integer.class)).isEqualTo(11);
        assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname = 'payment_fresh_schema'", String.class))
                .contains("ix_payment_attempts_order_history", "uq_payment_attempts_one_active_per_order",
                        "uq_payment_reconciliation_open_order");
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema = 'payment_fresh_schema' AND table_name = 'orders'", String.class))
                .contains("shipping_amount_vnd", "shipping_confirmation_reference", "payable_amount_vnd", "payment_total_frozen_at");
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema = 'payment_fresh_schema' AND table_name = 'payments'", String.class))
                .contains("amount", "amount_vnd", "method", "status", "paid_at");
        assertThat(throughV11.migrate().migrationsExecuted).isZero();
    }

    @Test
    void v10UpgradeBackfillsLegacyAttemptsConservativelyAndPreservesPaymentsAndOrders() {
        DataSource dataSource = dataSource("payment_upgrade_schema");
        Flyway throughV10 = flyway(dataSource, "payment_upgrade_schema", "10");
        assertThat(throughV10.migrate().migrationsExecuted).isEqualTo(10);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        LegacyFixture pending = insertLegacyPayment(jdbc, "pending", "PENDING", "VNPAY", 125000d, "PENDING");
        LegacyFixture success = insertLegacyPayment(jdbc, "success", "SUCCESS", "MOMO", 250000d, "CANCELED");
        LegacyFixture fractional = insertLegacyPayment(jdbc, "fractional", "FAILED", "VNPAY", 10.5d, "PROCESSING");
        LegacyFixture noProvider = insertLegacyPayment(jdbc, "no-provider", "FAILED", null, 500d, "PENDING");
        LegacyFixture unsafe = insertLegacyPayment(jdbc, "unsafe", "SUCCESS", "MOMO", 9007199254740992d, "CANCELED");
        LegacyFixture invalidPending = insertLegacyPayment(jdbc, "invalid-pending", "PENDING", null, Double.NaN, "PENDING");
        LegacyFixture preexistingVnd = insertLegacyPayment(jdbc, "preserved-vnd", "FAILED", "VNPAY", 777d, "PENDING");
        jdbc.update("UPDATE payments SET amount_vnd = 778 WHERE payment_id = ?", preexistingVnd.paymentId());
        Map<Integer, LegacyFixture> fixtures = Map.of(
                pending.paymentId(), pending, success.paymentId(), success, fractional.paymentId(), fractional,
                noProvider.paymentId(), noProvider, unsafe.paymentId(), unsafe,
                invalidPending.paymentId(), invalidPending, preexistingVnd.paymentId(), preexistingVnd);

        Flyway throughV11 = flyway(dataSource, "payment_upgrade_schema", "11");
        assertThat(throughV11.migrate().migrationsExecuted).isEqualTo(1);
        throughV11.validate();

        for (LegacyFixture fixture : fixtures.values()) {
            Map<String, Object> payment = jdbc.queryForMap("SELECT amount, amount_vnd, method, status, paid_at FROM payments WHERE payment_id = ?", fixture.paymentId());
            double preservedAmount = ((Number) payment.get("amount")).doubleValue();
            if (Double.isNaN(fixture.amount())) assertThat(preservedAmount).isNaN();
            else assertThat(preservedAmount).isEqualTo(fixture.amount());
            Long expectedPaymentVnd = expectedPaymentVnd(fixture);
            if (expectedPaymentVnd == null) assertThat(payment.get("amount_vnd")).isNull();
            else assertThat(((Number) payment.get("amount_vnd")).longValue()).isEqualTo(expectedPaymentVnd);
            assertThat(payment.get("method")).isEqualTo(fixture.method());
            assertThat(payment.get("status")).isEqualTo(fixture.status());
            assertThat(payment.get("paid_at")).isEqualTo(Timestamp.valueOf("2026-01-02 03:04:05"));
            assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE order_id = ?", String.class, fixture.orderId()))
                    .isEqualTo(fixture.orderStatus());
        }

        assertAttempt(jdbc, pending, "UNKNOWN", "VNPAY", 125000L);
        assertAttempt(jdbc, success, "LEGACY_REVIEW", "MOMO", 250000L);
        assertAttempt(jdbc, fractional, "LEGACY_REVIEW", "VNPAY", null);
        assertAttempt(jdbc, noProvider, "LEGACY_REVIEW", "UNKNOWN", 500L);
        assertAttempt(jdbc, unsafe, "LEGACY_REVIEW", "MOMO", null);
        assertAttempt(jdbc, invalidPending, "UNKNOWN", "UNKNOWN", null);
        assertAttempt(jdbc, preexistingVnd, "LEGACY_REVIEW", "VNPAY", 778L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempts", Integer.class)).isEqualTo(fixtures.size());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE shipping_amount_vnd IS NOT NULL OR payable_amount_vnd IS NOT NULL OR payment_total_frozen_at IS NOT NULL", Integer.class)).isZero();
    }

    @Test
    void activeAttemptCallbackAndUnresolvedReconciliationUniquenessAreDatabaseEnforced() {
        DataSource dataSource = dataSource("payment_constraints_schema");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        flyway(dataSource, "payment_constraints_schema", "11").migrate();
        LegacyFixture first = insertLegacyPayment(jdbc, "constraint-first", "FAILED", "VNPAY", 100d, "PENDING");
        LegacyFixture second = insertLegacyPayment(jdbc, "constraint-second", "FAILED", "MOMO", 100d, "PENDING");
        String firstAttempt = insertAttempt(jdbc, first, "VNPAY", "INITIATED", "first-idempotency");
        String secondAttempt = insertAttempt(jdbc, second, "MOMO", "PENDING", "second-idempotency");

        assertThatThrownBy(() -> insertAttempt(jdbc, first, "VNPAY", "FAILED", "first-idempotency"))
                .isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO payment_attempts(attempt_id, payment_id, order_id, owner_id, provider, amount_vnd,
                  status, idempotency_key, request_fingerprint)
                VALUES (gen_random_uuid(), ?, ?, ?, 'VNPAY', 100, 'INITIATED', 'new-active', 'fingerprint')
                """, first.paymentId(), first.orderId(), first.ownerId())).isInstanceOf(DuplicateKeyException.class);
        jdbc.update("UPDATE payment_attempts SET status = 'FAILED', resolved_at = CURRENT_TIMESTAMP WHERE attempt_id = ?::uuid", firstAttempt);
        jdbc.update("""
                INSERT INTO payment_attempts(attempt_id, payment_id, order_id, owner_id, provider, amount_vnd,
                  status, idempotency_key, request_fingerprint)
                VALUES (gen_random_uuid(), ?, ?, ?, 'VNPAY', 100, 'INITIATED', 'new-active', 'fingerprint')
                """, first.paymentId(), first.orderId(), first.ownerId());

        jdbc.update("""
                INSERT INTO payment_callback_events(provider, provider_event_key, attempt_id, event_digest, verified_at)
                VALUES ('MOMO', 'event-one', ?::uuid, repeat('a', 64), CURRENT_TIMESTAMP)
                """, secondAttempt);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO payment_callback_events(provider, provider_event_key, attempt_id, event_digest, verified_at)
                VALUES ('MOMO', 'event-one', ?::uuid, repeat('b', 64), CURRENT_TIMESTAMP)
                """, secondAttempt)).isInstanceOf(DuplicateKeyException.class);

        String openCaseId = "00000000-0000-0000-0000-000000000101";
        jdbc.update("""
                INSERT INTO payment_reconciliation_cases(case_id, attempt_id, order_id, provider, provider_event_key,
                  event_digest, observed_amount_vnd, verification_source, verified_at, conflict_reason, resolution_state)
                VALUES (?::uuid, ?::uuid, ?, 'MOMO', 'late-success-one', repeat('c', 64), 100, 'verified-fixture',
                  CURRENT_TIMESTAMP, 'late success', 'OPEN')
                """, openCaseId, secondAttempt, second.orderId());
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO payment_reconciliation_cases(case_id, attempt_id, order_id, provider, provider_event_key,
                  event_digest, observed_amount_vnd, verification_source, verified_at, conflict_reason, resolution_state)
                VALUES (gen_random_uuid(), ?::uuid, ?, 'MOMO', 'late-success-two', repeat('d', 64), 100, 'verified-fixture',
                  CURRENT_TIMESTAMP, 'late success', 'OPEN')
                """, secondAttempt, second.orderId())).isInstanceOf(DuplicateKeyException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempts WHERE order_id = ? AND status = 'INITIATED'", Integer.class, first.orderId())).isEqualTo(1);
    }

    @Test
    void concurrentActiveAttemptInsertsForOneOrderAllowOnlyOneWinner() throws Exception {
        DataSource dataSource = dataSource("payment_concurrent_schema");
        Flyway flyway = flyway(dataSource, "payment_concurrent_schema", "10");
        flyway.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        LegacyFixture legacy = insertLegacyPayment(jdbc, "concurrent", "FAILED", "VNPAY", 100d, "PENDING");
        flyway(dataSource, "payment_concurrent_schema", "11").migrate();
        jdbc.update("UPDATE payment_attempts SET status = 'FAILED', resolved_at = CURRENT_TIMESTAMP WHERE payment_id = ?", legacy.paymentId());

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        try {
            Future<Boolean> first = workers.submit(() -> tryConcurrentInsert(transaction, jdbc, legacy, "concurrent-key-a", ready, start));
            Future<Boolean> second = workers.submit(() -> tryConcurrentInsert(transaction, jdbc, legacy, "concurrent-key-b", ready, start));
            ready.await();
            start.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
        } finally {
            workers.shutdownNow();
        }

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempts WHERE order_id = ? AND status = 'INITIATED'", Integer.class, legacy.orderId())).isEqualTo(1);
    }

    private void assertAttempt(JdbcTemplate jdbc, LegacyFixture fixture, String status, String provider, Long amountVnd) {
        Map<String, Object> attempt = jdbc.queryForMap("""
                SELECT source_payment_status, status, provider, amount_vnd, idempotency_key
                FROM payment_attempts WHERE payment_id = ?
                """, fixture.paymentId());
        assertThat(attempt.get("source_payment_status")).isEqualTo(fixture.status());
        assertThat(attempt.get("status")).isEqualTo(status);
        assertThat(attempt.get("provider")).isEqualTo(provider);
        assertThat(attempt.get("amount_vnd")).isEqualTo(amountVnd);
        assertThat(attempt.get("idempotency_key")).isEqualTo("legacy-payment:" + fixture.paymentId());
    }

    private String insertAttempt(JdbcTemplate jdbc, LegacyFixture fixture, String provider, String status, String idempotencyKey) {
        return jdbc.queryForObject("""
                INSERT INTO payment_attempts(attempt_id, payment_id, order_id, owner_id, provider, amount_vnd,
                  status, idempotency_key, request_fingerprint)
                VALUES (gen_random_uuid(), ?, ?, ?, ?, 100, ?, ?, 'test-fingerprint')
                RETURNING attempt_id::text
                """, String.class, fixture.paymentId(), fixture.orderId(), fixture.ownerId(), provider, status, idempotencyKey);
    }

    private boolean tryConcurrentInsert(TransactionTemplate transaction, JdbcTemplate jdbc, LegacyFixture fixture,
                                        String idempotencyKey, CountDownLatch ready, CountDownLatch start) {
        try {
            return Boolean.TRUE.equals(transaction.execute(status -> {
                ready.countDown();
                try {
                    start.await();
                    jdbc.update("""
                            INSERT INTO payment_attempts(attempt_id, payment_id, order_id, owner_id, provider,
                              amount_vnd, status, idempotency_key, request_fingerprint)
                            VALUES (gen_random_uuid(), ?, ?, ?, 'VNPAY', 100, 'INITIATED', ?, 'concurrent-fingerprint')
                            """, fixture.paymentId(), fixture.orderId(), fixture.ownerId(), idempotencyKey);
                    return true;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }));
        } catch (DuplicateKeyException conflict) {
            return false;
        }
    }

    private Long expectedPaymentVnd(LegacyFixture fixture) {
        if (fixture == null) return null;
        if (fixture.status().equals("PENDING") && fixture.method() != null && fixture.amount() == 125000d) return 125000L;
        if (fixture.status().equals("SUCCESS") && fixture.method().equals("MOMO") && fixture.amount() == 250000d) return 250000L;
        if (fixture.method() == null && fixture.amount() == 500d) return 500L;
        if (fixture.amount() == 777d) return 778L;
        return null;
    }

    private LegacyFixture insertLegacyPayment(JdbcTemplate jdbc, String suffix, String status, String method, double amount, String orderStatus) {
        Integer userId = jdbc.queryForObject("""
                INSERT INTO users(name, email, password, role, status) VALUES (?, ?, 'test-only', 'USER', 'ACTIVE') RETURNING user_id
                """, Integer.class, "payment " + suffix, "payment-" + suffix + "@example.invalid");
        Integer printerId = jdbc.queryForObject("INSERT INTO printers(name, status) VALUES (?, 'ACTIVE') RETURNING printer_id", Integer.class, "printer " + suffix);
        Integer orderId = jdbc.queryForObject("""
                INSERT INTO orders(user_id, printer_id, total_amount, status, order_date)
                VALUES (?, ?, 100, ?, TIMESTAMP '2026-01-01 00:00:00') RETURNING order_id
                """, Integer.class, userId, printerId, orderStatus);
        Integer paymentId = jdbc.queryForObject("""
                INSERT INTO payments(order_id, amount, method, status, paid_at)
                VALUES (?, ?, ?, ?, TIMESTAMP '2026-01-02 03:04:05') RETURNING payment_id
                """, Integer.class, orderId, amount, method, status);
        return new LegacyFixture(paymentId, orderId, userId, amount, method, status, orderStatus);
    }

    private DataSource dataSource(String schema) {
        return new DriverManagerDataSource(POSTGRES.getJdbcUrl() + "&currentSchema=" + schema, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private Flyway flyway(DataSource dataSource, String schema, String target) {
        var config = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas(schema).defaultSchema(schema).cleanDisabled(true);
        if (target != null) config.target(MigrationVersion.fromVersion(target));
        return config.load();
    }

    private record LegacyFixture(Integer paymentId, Integer orderId, Integer ownerId, double amount,
                                 String method, String status, String orderStatus) { }
}
