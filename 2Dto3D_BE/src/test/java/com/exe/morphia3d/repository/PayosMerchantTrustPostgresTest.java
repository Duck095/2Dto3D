package com.exe.morphia3d.repository;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.exe.morphia3d.service.payment.payos.PayosMerchantBindingService;

import javax.sql.DataSource;
import java.util.UUID;
import java.sql.Timestamp;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class PayosMerchantTrustPostgresTest {
    private static final String FRESH_SCHEMA = "payos_trust_fresh";
    private static final String UPGRADE_SCHEMA = "payos_trust_upgrade";
    private static final String ROLE_SCHEMA = "payos_trust_roles";
    private static final String RUNTIME_ROLE = "payos_trust_runtime_test";
    private static final String FUNCTION_RUNTIME_ROLE = "payos_trust_function_runtime_test";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("payos_merchant_trust_test")
            .withUsername("payos_merchant_trust_test")
            .withPassword("payos_merchant_trust_test_only");

    @Test
    void freshV1ThroughV13AndUpgradeV1ThroughV12PreserveExistingTestOnlyLinks() {
        DataSource fresh = dataSource(FRESH_SCHEMA);
        assertThat(flyway(fresh, FRESH_SCHEMA, "12").migrate().migrationsExecuted).isEqualTo(12);
        assertThat(flyway(fresh, FRESH_SCHEMA, "13").migrate().migrationsExecuted).isEqualTo(1);
        flyway(fresh, FRESH_SCHEMA, "13").validate();
        JdbcTemplate freshJdbc = new JdbcTemplate(fresh);
        assertThat(freshJdbc.queryForObject("SELECT MAX(version::integer) FROM " + FRESH_SCHEMA + ".flyway_schema_history WHERE success", Integer.class))
                .isEqualTo(13);
        assertThat(freshJdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = ?", String.class, FRESH_SCHEMA))
                .contains("payos_merchant_bindings", "payos_merchant_binding_audit");
        assertThat(freshJdbc.queryForObject("SELECT NOT rolcanlogin AND NOT rolinherit AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls FROM pg_roles WHERE rolname = 'payos_trust_owner'", Boolean.class))
                .isTrue();

        DataSource upgrade = dataSource(UPGRADE_SCHEMA);
        flyway(upgrade, UPGRADE_SCHEMA, "12").migrate();
        JdbcTemplate upgradeJdbc = new JdbcTemplate(upgrade);
        UUID attemptId = insertPayosAttempt(upgradeJdbc, "legacy-link");
        long orderCode = upgradeJdbc.queryForObject("SELECT nextval('" + UPGRADE_SCHEMA + ".payos_order_code_seq')", Long.class);
        upgradeJdbc.update("""
                INSERT INTO payos_payment_links(attempt_id, order_code, merchant_channel_id, amount_vnd,
                  request_fingerprint, description, expires_at)
                VALUES (?, ?, 'legacy-channel', 500, repeat('a',64), 'VQR12345', CURRENT_TIMESTAMP + INTERVAL '1 hour')
                """, attemptId, orderCode);
        String before = upgradeJdbc.queryForObject("SELECT concat_ws('|', provenance, order_code, merchant_channel_id, amount_vnd, link_state) FROM payos_payment_links WHERE attempt_id = ?", String.class, attemptId);

        assertThat(flyway(upgrade, UPGRADE_SCHEMA, "13").migrate().migrationsExecuted).isEqualTo(1);
        flyway(upgrade, UPGRADE_SCHEMA, "13").validate();
        assertThat(upgradeJdbc.queryForObject("SELECT concat_ws('|', provenance, order_code, merchant_channel_id, amount_vnd, link_state) FROM payos_payment_links WHERE attempt_id = ?", String.class, attemptId))
                .isEqualTo(before);
        assertThat(upgradeJdbc.queryForObject("SELECT merchant_binding_id IS NULL AND merchant_binding_version IS NULL FROM payos_payment_links WHERE attempt_id = ?", Boolean.class, attemptId))
                .isTrue();
    }

    @Test
    void runtimeRoleCannotForgeBindingsChangeProvenanceOrMutateAudit() throws Exception {
        DataSource dataSource = dataSource(ROLE_SCHEMA);
        flyway(dataSource, ROLE_SCHEMA, "13").migrate();
        SingleConnectionDataSource runtimeConnection = new SingleConnectionDataSource(dataSource.getConnection(), true);
        JdbcTemplate owner = new JdbcTemplate(runtimeConnection);
        UUID attemptId = insertPayosAttempt(owner, "runtime-link");
        long orderCode = owner.queryForObject("SELECT nextval('" + ROLE_SCHEMA + ".payos_order_code_seq')", Long.class);
        owner.update("""
                INSERT INTO payos_payment_links(attempt_id, order_code, merchant_channel_id, amount_vnd,
                  request_fingerprint, description, expires_at)
                VALUES (?, ?, 'runtime-channel', 500, repeat('b',64), 'VQR12345', CURRENT_TIMESTAMP + INTERVAL '1 hour')
                """, attemptId, orderCode);
        owner.execute("CREATE ROLE " + RUNTIME_ROLE + " LOGIN PASSWORD 'TEST_ONLY_ROLE_PASSWORD'");
        owner.execute("GRANT USAGE ON SCHEMA " + ROLE_SCHEMA + " TO " + RUNTIME_ROLE);
        owner.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA " + ROLE_SCHEMA + " TO " + RUNTIME_ROLE);
        owner.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA " + ROLE_SCHEMA + " TO " + RUNTIME_ROLE);
        assertThat(owner.queryForObject("SELECT NOT pg_has_role(?, 'payos_trust_owner', 'MEMBER')", Boolean.class, RUNTIME_ROLE)).isTrue();
        assertThat(owner.queryForObject("SELECT NOT has_function_privilege(?, 'payos_create_live_payment_link(uuid,text,bigint,text,text,timestamp with time zone,uuid,integer,text)', 'EXECUTE')", Boolean.class, RUNTIME_ROLE)).isTrue();
        UUID seedBindingId = UUID.randomUUID();
        owner.queryForObject("SELECT payos_register_merchant_binding(?,?,?,?,?,?,?,?,?,?)", UUID.class,
                seedBindingId, "audit-seed-channel", 1, "TEST_ONLY_CLIENT", "secret://test/api/v1",
                "secret://test/checksum/v1", "secret://test/version/v1", "970422", "c".repeat(64), "test-registration-evidence");
        owner.execute("SET ROLE " + RUNTIME_ROLE);

        assertThatThrownBy(() -> owner.update("UPDATE payos_payment_links SET provenance = 'LIVE' WHERE attempt_id = ?", attemptId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> owner.update("""
                INSERT INTO payos_payment_links(attempt_id, provenance, merchant_binding_id, merchant_binding_version,
                  order_code, merchant_channel_id, amount_vnd, request_fingerprint, description, expires_at)
                VALUES (?, 'LIVE', ?, 1, ?, 'runtime-channel', 500, repeat('d',64), 'VQR12345', CURRENT_TIMESTAMP + INTERVAL '1 hour')
                """, attemptId, UUID.randomUUID(), orderCode + 1))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> owner.update("""
                INSERT INTO payos_merchant_bindings(binding_id, merchant_channel_id, binding_version, environment,
                  binding_state, client_id, api_key_secret_ref, checksum_key_secret_ref, secret_version_ref,
                  receiving_bank_bin, receiving_account_fingerprint, created_by)
                VALUES (?, 'forged-channel', 1, 'PRODUCTION', 'VERIFIED', 'fake-client', 'secret://api',
                  'secret://checksum', 'v1', '970422', repeat('c',64), 'runtime')
                """, UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> owner.update("DELETE FROM payos_merchant_binding_audit"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> owner.update("UPDATE payos_merchant_binding_audit SET actor = 'tampered'"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> owner.update("UPDATE payos_merchant_bindings SET binding_state = 'VERIFIED' WHERE binding_id = ?", seedBindingId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> owner.update("""
                INSERT INTO payos_merchant_binding_audit(binding_id,binding_version,event_type,actor,evidence_ref)
                VALUES (?,1,'BINDING_VERIFIED','runtime','forged-evidence')
                """, seedBindingId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> owner.queryForObject("SELECT payos_create_live_payment_link(?,?,?,?,?,?,?,?,?)", UUID.class,
                attemptId, "runtime-channel", 500L, "d".repeat(64), "VQR12345", Timestamp.from(java.time.Instant.now().plusSeconds(3600)), UUID.randomUUID(), 1, "e".repeat(64)))
                .isInstanceOf(DataAccessException.class);

        owner.execute("RESET ROLE");
        assertThat(owner.queryForObject("SELECT provenance FROM payos_payment_links WHERE attempt_id = ?", String.class, attemptId))
                .isEqualTo("TEST_ONLY");
        assertThat(owner.queryForObject("SELECT COUNT(*) FROM payos_merchant_bindings", Integer.class)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT COUNT(*) FROM payos_merchant_binding_audit", Integer.class)).isEqualTo(1);
        runtimeConnection.destroy();
    }

    @Test
    void concurrentLinkCreationAndMerchantVersionChangeSerializeOnChannel() throws Exception {
        DataSource dataSource = dataSource("payos_trust_race_test");
        flyway(dataSource, "payos_trust_race_test", "13").migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID bindingV1 = registerAndVerify(jdbc, "racing-channel", 1, "racing-operator");
        UUID racingAttempt = insertPayosAttempt(jdbc, "racing-attempt");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var create = workers.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    createLiveLink(jdbc, racingAttempt, "racing-channel", bindingV1, 1, "d".repeat(64));
                    return true;
                } catch (DataAccessException rejectedByVersionRace) {
                    return false;
                }
            });
            var versionChange = workers.submit(() -> {
                ready.countDown();
                start.await();
                registerBinding(jdbc, "racing-channel", 2, "version-operator");
                return true;
            });
            ready.await();
            start.countDown();
            boolean oldVersionCreatedBeforeChange = create.get();
            assertThat(versionChange.get()).isTrue();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payos_payment_links WHERE attempt_id = ?", Integer.class, racingAttempt))
                    .isEqualTo(oldVersionCreatedBeforeChange ? 1 : 0);

            UUID bindingV2 = jdbc.queryForObject("SELECT binding_id FROM payos_merchant_bindings WHERE merchant_channel_id = 'racing-channel' AND binding_version = 2", UUID.class);
            assertThatThrownBy(() -> createLiveLink(jdbc, insertPayosAttempt(jdbc, "old-version-after-switch"),
                    "racing-channel", bindingV1, 1, "b".repeat(64))).isInstanceOf(DataAccessException.class);
            transitionBinding(jdbc, bindingV2, "VERIFIED", "system-operator", "binding-v2-verified");
            UUID currentAttempt = insertPayosAttempt(jdbc, "current-version-after-switch");
            createLiveLink(jdbc, currentAttempt, "racing-channel", bindingV2, 2, "d".repeat(64));
            assertThat(jdbc.queryForObject("SELECT provenance FROM payos_payment_links WHERE attempt_id = ?", String.class, currentAttempt))
                    .isEqualTo("LIVE");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM payos_merchant_binding_audit WHERE event_type = 'LIVE_LINK_AUTHORIZED'", Integer.class))
                    .isEqualTo(oldVersionCreatedBeforeChange ? 2 : 1);
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void verifiedBindingFunctionRequiresEvidenceAndRejectsRevokedOrCrossChannelLinks() {
        DataSource dataSource = dataSource("payos_trust_binding_test");
        flyway(dataSource, "payos_trust_binding_test", "13").migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID attemptId = insertPayosAttempt(jdbc, "binding-link");
        UUID bindingId = UUID.randomUUID();
        jdbc.queryForObject("SELECT payos_register_merchant_binding(?,?,?,?,?,?,?,?,?,?)", UUID.class,
                bindingId, "bound-channel", 1, "verified-client-id", "secret://merchant/api-key/v1",
                "secret://merchant/checksum/v1", "secret://merchant/version/v1", "970422", "e".repeat(64), "merchant-evidence-1");
        assertThatThrownBy(() -> execute(jdbc, "SELECT payos_transition_merchant_binding(?,?,?)",
                bindingId, "VERIFIED", ""))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.queryForObject("SELECT payos_create_live_payment_link(?,?,?,?,?,?,?,?,?)", UUID.class,
                attemptId, "bound-channel", 500L, "f".repeat(64), "VQR12345", Timestamp.from(java.time.Instant.now().plusSeconds(3600)), bindingId, 1, "e".repeat(64)))
                .isInstanceOf(DataAccessException.class);
        execute(jdbc, "SELECT payos_transition_merchant_binding(?,?,?)", bindingId, "VERIFIED", "merchant-verification-2");
        assertThat(jdbc.queryForObject("SELECT approved_by = session_user FROM payos_merchant_bindings WHERE binding_id = ?", Boolean.class, bindingId)).isTrue();
        PayosMerchantBindingService bindingService = new PayosMerchantBindingService(jdbc);
        assertThat(bindingService.findVerifiedBinding("bound-channel", 1, "970422", "e".repeat(64))).isPresent();
        assertThat(bindingService.findVerifiedBinding("other-channel", 1, "970422", "e".repeat(64))).isEmpty();
        assertThat(bindingService.findVerifiedBinding("bound-channel", 1, "999999", "e".repeat(64))).isEmpty();
        assertThatThrownBy(() -> jdbc.queryForObject("SELECT payos_create_live_payment_link(?,?,?,?,?,?,?,?,?)", UUID.class,
                attemptId, "other-channel", 500L, "f".repeat(64), "VQR12345", Timestamp.from(java.time.Instant.now().plusSeconds(3600)), bindingId, 1, "e".repeat(64)))
                .isInstanceOf(DataAccessException.class);
        UUID liveAttempt = insertPayosAttempt(jdbc, "verified-live-link");
        assertThat(jdbc.queryForObject("SELECT payos_create_live_payment_link(?,?,?,?,?,?,?,?,?)", UUID.class,
                liveAttempt, "bound-channel", 500L, "f".repeat(64), "VQR12345", Timestamp.from(java.time.Instant.now().plusSeconds(3600)), bindingId, 1, "e".repeat(64)))
                .isEqualTo(liveAttempt);
        assertThat(jdbc.queryForObject("SELECT provenance FROM payos_payment_links WHERE attempt_id = ?", String.class, liveAttempt)).isEqualTo("LIVE");
        assertThat(jdbc.queryForObject("SELECT event_type FROM payos_merchant_binding_audit WHERE attempt_id = ?", String.class, liveAttempt))
                .isEqualTo("LIVE_LINK_AUTHORIZED");
        UUID bindingV2 = registerBinding(jdbc, "bound-channel", 2, "second-binding-operator");
        assertThat(bindingService.findVerifiedBinding("bound-channel", 1, "970422", "e".repeat(64))).isEmpty();
        UUID blockedByPendingVersion = insertPayosAttempt(jdbc, "blocked-by-pending-binding-v2");
        assertThatThrownBy(() -> createLiveLink(jdbc, blockedByPendingVersion, "bound-channel", bindingId, 1, "f".repeat(64)))
                .isInstanceOf(DataAccessException.class);
        transitionBinding(jdbc, bindingV2, "VERIFIED", "second-binding-operator", "binding-v2-evidence");
        assertThat(bindingService.findVerifiedBinding("bound-channel", 1, "970422", "e".repeat(64))).isEmpty();
        assertThat(bindingService.findVerifiedBinding("bound-channel", 2, "970422", "d".repeat(64))).isPresent();
        execute(jdbc, "SELECT payos_transition_merchant_binding(?,?,?)", bindingId, "REVOKED", "merchant-revocation-3");
        assertThat(bindingService.findVerifiedBinding("bound-channel", 2, "970422", "d".repeat(64))).isPresent();
        UUID revokedAttempt = insertPayosAttempt(jdbc, "revoked-live-link");
        assertThatThrownBy(() -> jdbc.queryForObject("SELECT payos_create_live_payment_link(?,?,?,?,?,?,?,?,?)", UUID.class,
                revokedAttempt, "bound-channel", 500L, "f".repeat(64), "VQR12345", Timestamp.from(java.time.Instant.now().plusSeconds(3600)), bindingId, 1, "e".repeat(64)))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void securityDefinerFunctionsCannotBeShadowedByTemporaryRelations() throws Exception {
        String schema = "payos_trust_shadow_test";
        DataSource dataSource = dataSource(schema);
        flyway(dataSource, schema, "13").migrate();
        JdbcTemplate owner = new JdbcTemplate(dataSource);
        UUID attemptId = insertPayosAttempt(owner, "shadow-runtime");
        owner.execute("CREATE ROLE " + FUNCTION_RUNTIME_ROLE + " LOGIN PASSWORD 'TEST_ONLY_ROLE_PASSWORD'");
        owner.execute("GRANT USAGE ON SCHEMA " + schema + " TO " + FUNCTION_RUNTIME_ROLE);
        owner.execute("GRANT EXECUTE ON FUNCTION " + schema + ".payos_register_merchant_binding(UUID,TEXT,INTEGER,TEXT,TEXT,TEXT,TEXT,TEXT,TEXT,TEXT) TO " + FUNCTION_RUNTIME_ROLE);
        owner.execute("GRANT EXECUTE ON FUNCTION " + schema + ".payos_transition_merchant_binding(UUID,TEXT,TEXT) TO " + FUNCTION_RUNTIME_ROLE);
        owner.execute("GRANT EXECUTE ON FUNCTION " + schema + ".payos_create_live_payment_link(UUID,TEXT,BIGINT,TEXT,TEXT,TIMESTAMP WITH TIME ZONE,UUID,INTEGER,TEXT) TO " + FUNCTION_RUNTIME_ROLE);
        assertThat(owner.queryForObject("SELECT NOT pg_has_role(?, 'payos_trust_owner', 'MEMBER')", Boolean.class, FUNCTION_RUNTIME_ROLE)).isTrue();

        SingleConnectionDataSource callerConnection = new SingleConnectionDataSource(dataSource.getConnection(), true);
        JdbcTemplate caller = new JdbcTemplate(callerConnection);
        caller.execute("SET ROLE " + FUNCTION_RUNTIME_ROLE);
        caller.execute("CREATE TEMP TABLE payos_merchant_bindings (binding_id UUID, merchant_channel_id TEXT, binding_version INTEGER, binding_state TEXT)");
        caller.execute("CREATE TEMP TABLE payos_merchant_binding_audit (event_type TEXT, actor TEXT, evidence_ref TEXT)");
        caller.execute("CREATE TEMP TABLE payment_attempts (attempt_id UUID, provider TEXT, status TEXT, amount_vnd BIGINT)");
        caller.execute("CREATE TEMP TABLE payos_payment_links (attempt_id UUID, provenance TEXT, order_code BIGINT)");
        caller.execute("CREATE TEMP SEQUENCE payos_order_code_seq START 900000000000");
        UUID forgedBindingId = UUID.randomUUID();
        caller.update("INSERT INTO pg_temp.payos_merchant_bindings(binding_id,merchant_channel_id,binding_version,binding_state) VALUES (?, 'forged-channel', 1, 'VERIFIED')", forgedBindingId);
        caller.update("INSERT INTO pg_temp.payment_attempts(attempt_id,provider,status,amount_vnd) VALUES (?, 'PAYOS', 'INITIATED', 500)", attemptId);
        assertThatThrownBy(() -> caller.queryForObject("SELECT " + schema + ".payos_create_live_payment_link(?,?,?,?,?,?,?,?,?)", UUID.class,
                attemptId, "forged-channel", 500L, "f".repeat(64), "VQR12345",
                Timestamp.from(java.time.Instant.now().plusSeconds(3600)), forgedBindingId, 1, "e".repeat(64)))
                .isInstanceOf(DataAccessException.class);

        UUID bindingId = caller.queryForObject("SELECT " + schema + ".payos_register_merchant_binding(?,?,?,?,?,?,?,?,?,?)", UUID.class,
                UUID.randomUUID(), "shadow-channel", 1, "client-1", "secret://merchant/api/v1",
                "secret://merchant/checksum/v1", "secret://merchant/version/v1", "970422", "e".repeat(64), "approved-evidence-v1");
        caller.queryForObject("SELECT " + schema + ".payos_transition_merchant_binding(?,?,?)", Object.class,
                bindingId, "VERIFIED", "verification-evidence-v1");
        caller.queryForObject("SELECT " + schema + ".payos_create_live_payment_link(?,?,?,?,?,?,?,?,?)", UUID.class,
                attemptId, "shadow-channel", 500L, "f".repeat(64), "VQR12345",
                Timestamp.from(java.time.Instant.now().plusSeconds(3600)), bindingId, 1, "e".repeat(64));

        assertThat(caller.queryForObject("SELECT COUNT(*) FROM pg_temp.payos_merchant_bindings WHERE binding_id = ? AND binding_state = 'VERIFIED'", Integer.class, forgedBindingId)).isEqualTo(1);
        assertThat(caller.queryForObject("SELECT COUNT(*) FROM pg_temp.payos_merchant_binding_audit", Integer.class)).isZero();
        assertThat(caller.queryForObject("SELECT COUNT(*) FROM pg_temp.payment_attempts WHERE attempt_id = ? AND status = 'INITIATED'", Integer.class, attemptId)).isEqualTo(1);
        assertThat(caller.queryForObject("SELECT COUNT(*) FROM pg_temp.payos_payment_links", Integer.class)).isZero();
        assertThat(caller.queryForObject("SELECT last_value FROM pg_temp.payos_order_code_seq", Long.class)).isEqualTo(900000000000L);
        assertThat(owner.queryForObject("SELECT COUNT(*) FROM " + schema + ".payos_merchant_bindings WHERE binding_id = ? AND binding_state = 'VERIFIED'", Integer.class, bindingId)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT COUNT(*) FROM " + schema + ".payos_merchant_binding_audit WHERE binding_id = ?", Integer.class, bindingId)).isEqualTo(3);
        assertThat(owner.queryForObject("SELECT provenance FROM " + schema + ".payos_payment_links WHERE attempt_id = ?", String.class, attemptId)).isEqualTo("LIVE");
        for (String function : java.util.List.of("payos_register_merchant_binding", "payos_transition_merchant_binding", "payos_create_live_payment_link")) {
            String signature = owner.queryForObject("SELECT proconfig[1] FROM pg_proc WHERE pronamespace = ?::regnamespace AND proname = ?", String.class, schema, function);
            assertThat(signature).contains("search_path=pg_catalog, " + schema + ", pg_temp");
        }
        caller.execute("RESET ROLE");
        callerConnection.destroy();
        JdbcTemplate afterReconnect = new JdbcTemplate(dataSource);
        assertThat(afterReconnect.queryForObject("SELECT provenance FROM " + schema + ".payos_payment_links WHERE attempt_id = ?", String.class, attemptId))
                .isEqualTo("LIVE");
        assertThat(afterReconnect.queryForObject("SELECT COUNT(*) FROM " + schema + ".payos_merchant_binding_audit WHERE attempt_id = ? AND event_type = 'LIVE_LINK_AUTHORIZED'", Integer.class, attemptId))
                .isEqualTo(1);
    }

    private UUID insertPayosAttempt(JdbcTemplate jdbc, String suffix) {
        Integer ownerId = jdbc.queryForObject("INSERT INTO users(name,email,password,role,status) VALUES (?, ?, 'test-only', 'USER', 'ACTIVE') RETURNING user_id", Integer.class, suffix, suffix + "@example.invalid");
        Integer printerId = jdbc.queryForObject("INSERT INTO printers(name,status) VALUES (?, 'ACTIVE') RETURNING printer_id", Integer.class, "printer-" + suffix);
        Integer orderId = jdbc.queryForObject("INSERT INTO orders(user_id,printer_id,total_amount,total_amount_vnd,status) VALUES (?,?,500,500,'PENDING') RETURNING order_id", Integer.class, ownerId, printerId);
        Integer paymentId = jdbc.queryForObject("INSERT INTO payments(order_id,amount,amount_vnd,method,status) VALUES (?,500,500,'BANK_TRANSFER','PENDING') RETURNING payment_id", Integer.class, orderId);
        UUID attemptId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO payment_attempts(attempt_id,payment_id,order_id,owner_id,provider,amount_vnd,status,idempotency_key,request_fingerprint)
                VALUES (?, ?, ?, ?, 'PAYOS', 500, 'INITIATED', ?, 'payos-trust-test')
                """, attemptId, paymentId, orderId, ownerId, "trust:" + suffix);
        return attemptId;
    }

    private DataSource dataSource(String schema) {
        return new DriverManagerDataSource(POSTGRES.getJdbcUrl() + "&currentSchema=" + schema,
                POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private void execute(JdbcTemplate jdbc, String sql, Object... values) {
        jdbc.execute(sql, (org.springframework.jdbc.core.PreparedStatementCallback<Void>) statement -> {
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            statement.execute();
            return null;
        });
    }

    private UUID registerAndVerify(JdbcTemplate jdbc, String channel, int version, String actor) {
        UUID bindingId = registerBinding(jdbc, channel, version, actor);
        transitionBinding(jdbc, bindingId, "VERIFIED", actor, "evidence-verified-" + version);
        return bindingId;
    }

    private UUID registerBinding(JdbcTemplate jdbc, String channel, int version, String actor) {
        return jdbc.queryForObject("SELECT payos_register_merchant_binding(?,?,?,?,?,?,?,?,?,?)", UUID.class,
                UUID.randomUUID(), channel, version, "client-" + version, "secret://merchant/api/v" + version,
                "secret://merchant/checksum/v" + version, "secret://merchant/version/v" + version,
                "970422", "d".repeat(64), "registration-evidence-v" + version);
    }

    private void transitionBinding(JdbcTemplate jdbc, UUID id, String state, String actor, String evidence) {
        execute(jdbc, "SELECT payos_transition_merchant_binding(?,?,?)", id, state, evidence);
    }

    private void createLiveLink(JdbcTemplate jdbc, UUID attemptId, String channel, UUID bindingId, int version, String fingerprint) {
        jdbc.queryForObject("SELECT payos_create_live_payment_link(?,?,?,?,?,?,?,?,?)", UUID.class,
                attemptId, channel, 500L, "f".repeat(64), "VQR12345",
                Timestamp.from(java.time.Instant.now().plusSeconds(3600)), bindingId, version, fingerprint);
    }

    private Flyway flyway(DataSource dataSource, String schema, String target) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas(schema).defaultSchema(schema).cleanDisabled(true)
                .target(MigrationVersion.fromVersion(target)).load();
    }
}
