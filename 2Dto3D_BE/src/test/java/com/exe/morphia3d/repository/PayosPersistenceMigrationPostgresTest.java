package com.exe.morphia3d.repository;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class PayosPersistenceMigrationPostgresTest {
    private static final String SCHEMA = "payos_persistence_test";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("payos_persistence_test")
            .withUsername("payos_persistence_test")
            .withPassword("payos_persistence_test_only");

    @Test
    void freshV1ThroughV12CreatesAdditivePayosStorageAndValidates() {
        DataSource dataSource = dataSource(SCHEMA);

        Flyway throughV11 = flyway(dataSource, SCHEMA, "11");
        assertThat(throughV11.migrate().migrationsExecuted).isEqualTo(11);
        throughV11.validate();
        Flyway throughV12 = flyway(dataSource, SCHEMA, "12");
        assertThat(throughV12.migrate().migrationsExecuted).isEqualTo(1);
        throughV12.validate();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT MAX(version::integer) FROM " + SCHEMA + ".flyway_schema_history WHERE success", Integer.class))
                .isEqualTo(12);
        assertThat(jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = ?", String.class, SCHEMA))
                .contains("payos_payment_links", "payos_webhook_receipts", "payos_reconciliation_queries");
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = 'payos_payment_links'", String.class, SCHEMA))
                .contains("order_code", "payment_link_id", "checkout_url_ciphertext", "qr_payload_ciphertext",
                        "expires_at", "receiving_account_number_ciphertext", "encryption_key_version", "provenance");
        assertThat(jdbc.queryForObject("SELECT column_default FROM information_schema.columns WHERE table_schema = ? AND table_name = 'payos_payment_links' AND column_name = 'provenance'",
                String.class, SCHEMA)).contains("TEST_ONLY");
    }

    @Test
    void v11UpgradePreservesLegacyRowsAndAllowsPayosValues() {
        DataSource dataSource = dataSource("payos_upgrade_test");
        Flyway throughV11 = flyway(dataSource, "payos_upgrade_test", "11");
        throughV11.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        LegacyFixture legacy = insertLegacyVnpay(jdbc);
        String before = jdbc.queryForObject("SELECT concat_ws('|', method, status, amount_vnd, paid_at) FROM payments WHERE payment_id = ?",
                String.class, legacy.paymentId());

        Flyway throughV12 = flyway(dataSource, "payos_upgrade_test", "12");
        assertThat(throughV12.migrate().migrationsExecuted).isEqualTo(1);
        throughV12.validate();

        assertThat(jdbc.queryForObject("SELECT concat_ws('|', method, status, amount_vnd, paid_at) FROM payments WHERE payment_id = ?",
                String.class, legacy.paymentId())).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT provider FROM payment_attempts WHERE payment_id = ?", String.class, legacy.paymentId()))
                .isEqualTo("VNPAY");
        assertThat(jdbc.queryForObject("SELECT method FROM payments WHERE payment_id = ?", String.class, legacy.paymentId()))
                .isEqualTo("VNPAY");

        Integer ownerId = insertOwnerAndOrder(jdbc, "payos-new");
        Integer orderId = jdbc.queryForObject("SELECT max(order_id) FROM orders WHERE user_id = ?", Integer.class, ownerId);
        Integer paymentId = jdbc.queryForObject("INSERT INTO payments(order_id, amount, amount_vnd, method, status) VALUES (?, 500, 500, 'BANK_TRANSFER', 'PENDING') RETURNING payment_id",
                Integer.class, orderId);
        assertThat(paymentId).isPositive();
        assertThat(jdbc.queryForObject("SELECT nextval('payos_upgrade_test.payos_order_code_seq')", Long.class)).isPositive();
    }

    @Test
    void orderCodesAreUniqueAcrossConcurrentAllocations() throws Exception {
        DataSource dataSource = dataSource("payos_sequence_test");
        flyway(dataSource, "payos_sequence_test", "12").migrate();
        CountDownLatch ready = new CountDownLatch(12);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(12);
        try {
            List<Future<Long>> codes = java.util.stream.IntStream.range(0, 12)
                    .mapToObj(index -> workers.submit(() -> {
                        ready.countDown();
                        start.await();
                        return new JdbcTemplate(dataSource).queryForObject(
                                "SELECT nextval('payos_sequence_test.payos_order_code_seq')", Long.class);
                    })).toList();
            ready.await();
            start.countDown();
            assertThat(codes.stream().map(future -> {
                try { return future.get(); } catch (Exception error) { throw new IllegalStateException(error); }
            }).distinct()).hasSize(12);
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void paymentLinksAndVerifiedBankReferencesAreUniquelyConstrained() {
        DataSource dataSource = dataSource("payos_unique_test");
        flyway(dataSource, "payos_unique_test", "12").migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        PayosAttempt first = insertPayosAttempt(jdbc, "first");
        PayosAttempt second = insertPayosAttempt(jdbc, "second");
        String linkId = "payos-link-12345678";
        insertLink(jdbc, first, "channel-a", linkId);
        assertThatThrownBy(() -> insertLink(jdbc, second, "channel-a", linkId)).isInstanceOf(DuplicateKeyException.class);
        insertLink(jdbc, second, "channel-a", "other-link-12345678");

        insertMatchedReceipt(jdbc, first, "channel-a", "bank-txn-unique", "a".repeat(64));
        assertThatThrownBy(() -> jdbc.update("UPDATE payos_webhook_receipts SET provider_status = 'PENDING' WHERE bank_reference = ?", "bank-txn-unique"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertMatchedReceipt(jdbc, second, "channel-a", "bank-txn-unique", "b".repeat(64)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void duplicateReceiptInsertIsSerializedByUniqueConstraint() throws Exception {
        DataSource dataSource = dataSource("payos_receipt_race_test");
        flyway(dataSource, "payos_receipt_race_test", "12").migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        PayosAttempt attempt = insertPayosAttempt(jdbc, "receipt-race");
        insertLink(jdbc, attempt, "channel-a", "race-link-12345678");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> writes = java.util.stream.IntStream.range(0, 2)
                    .mapToObj(index -> workers.submit(() -> {
                        ready.countDown();
                        start.await();
                        try {
                            insertReceipt(jdbc, attempt, "channel-a", "same-bank-reference", "c".repeat(64));
                            return true;
                        } catch (DuplicateKeyException duplicate) { return false; }
                    })).toList();
            ready.await();
            start.countDown();
            assertThat(writes.stream().map(future -> {
                try { return future.get(); } catch (Exception error) { throw new IllegalStateException(error); }
            }).toList()).containsExactlyInAnyOrder(true, false);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payos_webhook_receipts WHERE bank_reference = 'same-bank-reference'", Integer.class))
                    .isEqualTo(1);
        } finally { workers.shutdownNow(); }
    }

    private LegacyFixture insertLegacyVnpay(JdbcTemplate jdbc) {
        Integer ownerId = insertOwnerAndOrder(jdbc, "legacy-vnpay");
        Integer orderId = jdbc.queryForObject("SELECT max(order_id) FROM orders WHERE user_id = ?", Integer.class, ownerId);
        Integer paymentId = jdbc.queryForObject("INSERT INTO payments(order_id, amount, amount_vnd, method, status, paid_at) VALUES (?, 1000, 1000, 'VNPAY', 'SUCCESS', CURRENT_TIMESTAMP) RETURNING payment_id", Integer.class, orderId);
        UUID attemptId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO payment_attempts(attempt_id, payment_id, order_id, owner_id, provider, amount_vnd, status,
                  idempotency_key, request_fingerprint, source_payment_status)
                VALUES (?, ?, ?, ?, 'VNPAY', 1000, 'LEGACY_REVIEW', ?, 'legacy-fingerprint', 'SUCCESS')
                """, attemptId, paymentId, orderId, ownerId, "legacy-vnpay:" + paymentId);
        jdbc.update("INSERT INTO payment_callback_events(provider, provider_event_key, attempt_id, event_digest, verified_at) VALUES ('VNPAY', 'legacy-event-' || ?, ?, repeat('a',64), CURRENT_TIMESTAMP)", paymentId, attemptId);
        jdbc.update("""
                INSERT INTO payment_reconciliation_cases(case_id, attempt_id, order_id, provider, provider_event_key,
                  event_digest, observed_amount_vnd, verification_source, verified_at, conflict_reason)
                VALUES (?, ?, ?, 'VNPAY', ?, repeat('b',64), 1000, 'legacy-test', CURRENT_TIMESTAMP, 'legacy review')
                """, UUID.randomUUID(), attemptId, orderId, "legacy-case:" + paymentId);
        return new LegacyFixture(paymentId);
    }

    private Integer insertOwnerAndOrder(JdbcTemplate jdbc, String suffix) {
        Integer ownerId = insertOwner(jdbc, suffix);
        Integer printerId = jdbc.queryForObject("INSERT INTO printers(name, status) VALUES (?, 'ACTIVE') RETURNING printer_id", Integer.class, "printer-" + suffix);
        jdbc.update("INSERT INTO orders(user_id, printer_id, total_amount, total_amount_vnd, status) VALUES (?, ?, 500, 500, 'PENDING')", ownerId, printerId);
        return ownerId;
    }

    private Integer insertOwner(JdbcTemplate jdbc, String suffix) {
        return jdbc.queryForObject("INSERT INTO users(name, email, password, role, status) VALUES (?, ?, 'test-only', 'USER', 'ACTIVE') RETURNING user_id",
                Integer.class, suffix, suffix + "@example.invalid");
    }

    private PayosAttempt insertPayosAttempt(JdbcTemplate jdbc, String suffix) {
        Integer ownerId = insertOwnerAndOrder(jdbc, suffix);
        Integer orderId = jdbc.queryForObject("SELECT max(order_id) FROM orders WHERE user_id = ?", Integer.class, ownerId);
        Integer paymentId = jdbc.queryForObject("INSERT INTO payments(order_id, amount, amount_vnd, method, status) VALUES (?, 500, 500, 'BANK_TRANSFER', 'PENDING') RETURNING payment_id", Integer.class, orderId);
        UUID attemptId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO payment_attempts(attempt_id, payment_id, order_id, owner_id, provider, amount_vnd, status,
                  idempotency_key, request_fingerprint)
                VALUES (?, ?, ?, ?, 'PAYOS', 500, 'INITIATED', ?, 'payos-fingerprint')
                """, attemptId, paymentId, orderId, ownerId, "payos-idem:" + suffix);
        return new PayosAttempt(attemptId, orderId, ownerId, paymentId);
    }

    private void insertLink(JdbcTemplate jdbc, PayosAttempt attempt, String channel, String paymentLinkId) {
        jdbc.update("""
                INSERT INTO payos_payment_links(attempt_id, merchant_channel_id, amount_vnd, request_fingerprint,
                  description, link_state, payment_link_id, checkout_url_ciphertext, qr_payload_ciphertext,
                  receiving_bank_bin, receiving_account_number_ciphertext, receiving_account_name,
                  encryption_key_version, link_created_at, expires_at)
                VALUES (?, ?, 500, repeat('a',64), 'VQR12345', 'LINK_CREATED', ?, 'cipher-url', 'cipher-qr',
                  '970422', 'cipher-account', 'TEST ACCOUNT', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '1 hour')
                """, attempt.attemptId(), channel, paymentLinkId);
    }

    private void insertMatchedReceipt(JdbcTemplate jdbc, PayosAttempt attempt, String channel, String bankReference, String digest) {
        insertReceipt(jdbc, attempt, channel, bankReference, digest);
    }

    private void insertReceipt(JdbcTemplate jdbc, PayosAttempt attempt, String channel, String bankReference, String digest) {
        jdbc.update("""
                INSERT INTO payos_webhook_receipts(receipt_id, merchant_channel_id, bank_reference, event_digest,
                  attempt_id, order_code, payment_link_id, amount_vnd, recipient_account_ciphertext,
                  encryption_key_version, provider_status, disposition, signature_verified, currency,
                  signature_verified_at, processed_at)
                SELECT ?, ?, ?, ?, attempt_id, order_code, payment_link_id, amount_vnd,
                  'cipher-account', 1, 'PAID', 'MATCHED', true, 'VND', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                FROM payos_payment_links WHERE attempt_id = ?
                """, UUID.randomUUID(), channel, bankReference, digest, attempt.attemptId());
    }

    private DataSource dataSource(String schema) {
        return new DriverManagerDataSource(POSTGRES.getJdbcUrl() + "&currentSchema=" + schema,
                POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private Flyway flyway(DataSource dataSource, String schema, String target) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas(schema).defaultSchema(schema).cleanDisabled(true)
                .target(MigrationVersion.fromVersion(target)).load();
    }

    private record LegacyFixture(Integer paymentId) { }
    private record PayosAttempt(UUID attemptId, Integer orderId, Integer ownerId, Integer paymentId) { }
}
