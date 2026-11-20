package com.exe.morphia3d.service;

import com.exe.morphia3d.Morphia3dApplication;
import com.exe.morphia3d.controller.PaymentApiExceptionHandler;
import com.exe.morphia3d.controller.PaymentController;
import com.exe.morphia3d.domain.payment.PaymentAttemptEvent;
import com.exe.morphia3d.dto.request.CreatePaymentAttemptRequest;
import com.exe.morphia3d.dto.response.PaymentAttemptResponse;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.PaymentProvider;
import com.exe.morphia3d.enums.PaymentMethod;
import com.exe.morphia3d.feature.FeatureGate;
import com.exe.morphia3d.repository.PaymentAttemptRepository;
import com.exe.morphia3d.repository.OrderRepository;
import com.exe.morphia3d.repository.PayosPaymentLinkRepository;
import com.exe.morphia3d.repository.PayosWebhookReceiptRepository;
import com.exe.morphia3d.repository.PayosReconciliationQueryRepository;
import com.exe.morphia3d.security.UserPrincipal;
import com.exe.morphia3d.service.impl.PaymentServiceImpl;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.exe.morphia3d.service.payment.payos.PayosPaymentPersistenceService;
import com.exe.morphia3d.service.payment.payos.PayosProtocol;
import com.exe.morphia3d.service.payment.payos.PayosGateway;
import com.exe.morphia3d.service.payment.payos.PayosTransport;
import com.exe.morphia3d.service.payment.payos.PayosTestOnlyPaymentAttemptGateway;
import com.exe.morphia3d.test.support.PostgresTestRoleProvisioning;

import java.util.List;
import java.util.UUID;
import java.time.Instant;
import java.time.Duration;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Service transaction and Order-lock concurrency checks against a disposable PostgreSQL schema. */
@SpringBootTest(classes = Morphia3dApplication.class, properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "app.features.payment.enabled=true",
        "app.pricing.approved-commercial-policy-versions=payment-test-approved",
        "app.payment.payos.webhook-checksum-keys.test-merchant-channel=1a54716c8f0efb2744fb28b6e38b25da7f67a925d98bc1c18bd8faaecadd7675",
        "app.payment.payos.encryption-keyring[1]=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.payment.payos.encryption-key-version=1"
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class PaymentAttemptPersistencePostgresConcurrencyTest {
    private static final AtomicInteger TEST_MERCHANT_BINDING_VERSION = new AtomicInteger();

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("payment_service_concurrency")
            .withUsername("payment_service_test")
            .withPassword("payment_service_test_only");

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
        properties.add("spring.flyway.schemas", () -> "public");
        properties.add("spring.flyway.default-schema", () -> "public");
    }

    private static synchronized void migrateIsolatedSchema() {
        String jdbcUrl = POSTGRES.getJdbcUrl();
        JdbcTemplate bootstrap = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword()));
        PostgresTestRoleProvisioning.provisionMigrationRoles(bootstrap, POSTGRES.getUsername());
        Flyway.configure()
                .dataSource(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .schemas("public")
                .defaultSchema("public")
                .cleanDisabled(true)
                .target(org.flywaydb.core.api.MigrationVersion.fromVersion("14"))
                .load().migrate();
        PostgresTestRoleProvisioning.grantSnapshotReferencePrivileges(bootstrap);
        Flyway.configure().dataSource(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").schemas("public").defaultSchema("public")
                .cleanDisabled(true).load().migrate();
    }

    @Autowired private PaymentAttemptPersistenceService persistence;
    @Autowired private PaymentReadinessService readiness;
    @Autowired private PaymentAttemptLifecycleService lifecycle;
    @Autowired private FeatureGate featureGate;
    @Autowired private PaymentAttemptRepository attempts;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private OrderRepository orders;
    @Autowired private PayosPaymentLinkRepository payosLinks;
    @Autowired private PayosWebhookReceiptRepository payosReceipts;
    @Autowired private PayosReconciliationQueryRepository payosQueries;
    @Autowired private PaymentAttemptStateService stateService;
    @Autowired private PayosPaymentPersistenceService payosPersistence;
    @Autowired private PayosWebhookProcessingService payosWebhookProcessing;

    @AfterEach
    void cleanupFixtures() {
        jdbc.update("DELETE FROM payment_callback_events");
        jdbc.update("DELETE FROM payment_reconciliation_cases");
        jdbc.update("DELETE FROM payos_reconciliation_queries");
        jdbc.update("DELETE FROM payos_webhook_receipts");
        jdbc.update("DELETE FROM payos_payment_links");
        jdbc.update("DELETE FROM payment_attempts");
        jdbc.update("DELETE FROM payments");
        jdbc.update("DELETE FROM order_items");
        jdbc.update("DELETE FROM orders");
        jdbc.update("DELETE FROM price_estimations");
        jdbc.update("DELETE FROM model_3d");
        jdbc.update("DELETE FROM materials");
        jdbc.update("DELETE FROM printers");
        jdbc.update("DELETE FROM users");
    }

    @Test
    void simultaneousSameKeyRequestsSerializeOnOrderAndPersistOneAttempt() throws Exception {
        Fixture fixture = seedPayableOrder();
        TestOnlyGateway gateway = new TestOnlyGateway();
        PaymentServiceImpl paymentService = new PaymentServiceImpl(featureGate, readiness, persistence, lifecycle,
                List.of(gateway));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<PaymentAttemptResponse> first = workers.submit(() -> createAtBarrier(paymentService, fixture, ready, start));
            Future<PaymentAttemptResponse> second = workers.submit(() -> createAtBarrier(paymentService, fixture, ready, start));
            ready.await();
            start.countDown();

            List<PaymentAttemptResponse> results = List.of(first.get(), second.get());
            assertThat(results.get(0).attemptId()).isEqualTo(results.get(1).attemptId());
            assertThat(results).extracting(response -> response.status().name())
                    .containsOnly("INITIATED", "PENDING");
            assertThat(gateway.submissions()).isEqualTo(1);
            assertThat(attempts.findByOrderIdOrderByCreatedAtDescAttemptIdDesc(fixture.orderId()))
                    .singleElement().extracting(attempt -> attempt.getStatus().name()).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempts WHERE order_id = ?",
                    Integer.class, fixture.orderId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE order_id = ?",
                    Integer.class, fixture.orderId())).isEqualTo(1);

            UUID attemptId = results.get(0).attemptId();
            jdbc.update("UPDATE payment_attempts SET status = 'UNKNOWN' WHERE attempt_id = ?", attemptId);
            assertRetryBlocked(paymentService, fixture, "retry-after-unknown");
            jdbc.update("UPDATE payment_attempts SET status = 'LEGACY_REVIEW' WHERE attempt_id = ?", attemptId);
            assertRetryBlocked(paymentService, fixture, "retry-after-legacy-review");
            jdbc.update("UPDATE payment_attempts SET status = 'FAILED', resolved_at = CURRENT_TIMESTAMP WHERE attempt_id = ?",
                    attemptId);
            jdbc.update("""
                    INSERT INTO payment_reconciliation_cases(case_id, attempt_id, order_id, provider,
                        provider_event_key, event_digest, observed_amount_vnd, verification_source,
                        verified_at, conflict_reason, resolution_state)
                    VALUES (?, ?, ?, 'PAYOS', 'late-success-reconciliation', repeat('a', 64), 125000,
                        'test-only', CURRENT_TIMESTAMP, 'unresolved test evidence', 'OPEN')
                    """, UUID.randomUUID(), attemptId, fixture.orderId());
            assertRetryBlocked(paymentService, fixture, "retry-during-reconciliation");

            Fixture callbackFixture = seedPayableOrder();
            PaymentAttemptResponse callbackAttempt = paymentService.createAttempt(callbackFixture.ownerId(),
                    new CreatePaymentAttemptRequest(callbackFixture.orderId(), PaymentMethod.BANK_TRANSFER), "callback-test-key");
            UUID callbackAttemptId = callbackAttempt.attemptId();
            String merchantReference = jdbc.queryForObject(
                    "SELECT merchant_reference FROM payment_attempts WHERE attempt_id = ?", String.class, callbackAttemptId);
            VerifiedPaymentEvent verifiedSuccess = new VerifiedPaymentEvent(PaymentProvider.PAYOS, callbackAttemptId,
                    merchantReference, "test-only-success-event", "a".repeat(64), 125000,
                    "test-only-provider-transaction", PaymentAttemptEvent.SUCCESS, Instant.now(), "TEST_ONLY");
            lifecycle.applyVerifiedEvent(verifiedSuccess);
            lifecycle.applyVerifiedEvent(verifiedSuccess);
            assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE order_id = ?", String.class,
                    callbackFixture.orderId())).isEqualTo("PROCESSING");
            assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE order_id = ?", String.class,
                    callbackFixture.orderId())).isEqualTo("SUCCESS");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_callback_events WHERE attempt_id = ?",
                    Integer.class, callbackAttemptId)).isEqualTo(1);
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void concurrentIdenticalVerifiedCallbacksAreRecordedAndAppliedOnce() throws Exception {
        Fixture fixture = seedPayableOrder();
        TestOnlyGateway gateway = new TestOnlyGateway();
        PaymentServiceImpl paymentService = new PaymentServiceImpl(featureGate, readiness, persistence, lifecycle, List.of(gateway));
        PaymentAttemptResponse created = paymentService.createAttempt(fixture.ownerId(),
                new CreatePaymentAttemptRequest(fixture.orderId(), PaymentMethod.BANK_TRANSFER), "concurrent-ipn-create");
        String merchantReference = jdbc.queryForObject("SELECT merchant_reference FROM payment_attempts WHERE attempt_id = ?",
                String.class, created.attemptId());
        VerifiedPaymentEvent verifiedSuccess = new VerifiedPaymentEvent(PaymentProvider.PAYOS, created.attemptId(),
                merchantReference, "provider-transaction-concurrent-1", "c".repeat(64), created.amountVnd(),
                "provider-transaction-concurrent-1", PaymentAttemptEvent.SUCCESS, Instant.now(), "TEST_ONLY_VERIFIER");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = workers.submit(() -> applyAtBarrier(verifiedSuccess, ready, start));
            Future<String> second = workers.submit(() -> applyAtBarrier(verifiedSuccess, ready, start));
            ready.await();
            start.countDown();
            assertThat(first.get()).isEqualTo("SUCCEEDED");
            assertThat(second.get()).isEqualTo("SUCCEEDED");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_callback_events WHERE attempt_id = ?",
                    Integer.class, created.attemptId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE order_id = ?", String.class,
                    fixture.orderId())).isEqualTo("PROCESSING");
            assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE order_id = ?", String.class,
                    fixture.orderId())).isEqualTo("SUCCESS");
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void payosIdentityIsDurableBeforeOneShotCreateAndUnknownIsNeverResent() {
        Fixture fixture = seedPayableOrder();
        var created = persistence.createOrReplay(fixture.ownerId(),
                new CreatePaymentAttemptRequest(fixture.orderId(), PaymentMethod.BANK_TRANSFER),
                "payos-persistence-only", true);
        UUID attemptId = created.attempt().getAttemptId();
        var first = payosPersistence.prepare(attemptId, "test-merchant-channel", "2DTO3D001",
                Instant.now().plus(Duration.ofMinutes(20)));
        var replay = payosPersistence.prepare(attemptId, "test-merchant-channel", "2DTO3D001",
                first.getExpiresAt());
        assertThat(replay.getOrderCode()).isEqualTo(first.getOrderCode());
        assertThat(attempts.findById(attemptId).orElseThrow().getStatus().name()).isEqualTo("INITIATED");
        assertThat(payosPersistence.claimCreate(attemptId)).isTrue();
        assertThat(payosPersistence.claimCreate(attemptId)).isFalse();
        payosPersistence.markCreateUnknown(attemptId);

        // A fresh service instance models process restart; durable SUBMITTING/UNKNOWN is not claimed again.
        assertThat(payosPersistence.claimCreate(attemptId)).isFalse();
        assertThat(payosLinks.findById(attemptId).orElseThrow().getLinkState()).isEqualTo("UNKNOWN");
        assertThat(attempts.findById(attemptId).orElseThrow().getStatus().name()).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payos_payment_links WHERE attempt_id = ?",
                Integer.class, attemptId)).isEqualTo(1);
    }

    @Test
    void payosCreatedLinkIsEncryptedAndOwnerScoped() throws Exception {
        Fixture fixture = seedPayableOrder();
        var created = persistence.createOrReplay(fixture.ownerId(),
                new CreatePaymentAttemptRequest(fixture.orderId(), PaymentMethod.BANK_TRANSFER),
                "payos-link-persistence", true);
        UUID attemptId = created.attempt().getAttemptId();
        var identity = prepareLiveTestLink(attemptId, "test-merchant-channel", "2DTO3D002",
                Instant.now().plus(Duration.ofMinutes(20)));
        assertThat(payosPersistence.claimCreate(attemptId)).isTrue();
        payosPersistence.recordCreated(attemptId, new PayosPaymentPersistenceService.CreatedLink(identity.getOrderCode(),
                identity.getAmountVnd(), "link-test-1", "https://payos.vn/web/abc", "000201-qr-payload",
                "9704", "123456789", "TEST ACCOUNT", identity.getExpiresAt(), "d".repeat(64)), Set.of("payos.vn"));
        var stored = payosLinks.findById(attemptId).orElseThrow();
        assertThat(stored.getCheckoutUrlCiphertext()).doesNotContain("https://payos.vn");
        assertThat(stored.getQrPayloadCiphertext()).doesNotContain("000201-qr-payload");
        assertThat(stored.getReceivingAccountNumberCiphertext()).doesNotContain("123456789");
        var view = payosPersistence.readOwned(attemptId, fixture.ownerId());
        assertThat(view.checkoutUrl()).isEqualTo("https://payos.vn/web/abc");
        assertThat(view.recipientAccountNumber()).isEqualTo("123456789");
        assertThatThrownBy(() -> payosPersistence.readOwned(attemptId, fixture.ownerId() + 1000))
                .isInstanceOf(RuntimeException.class);

        var paid = payosPersistence.recordVerifiedWebhook(PayosProtocol.verifyWebhookEvidence(
                signedWebhook(identity.getOrderCode(), identity.getAmountVnd(), "link-test-1", "123456789", "00"),
                "test-merchant-channel", TEST_PAYOS_CHECKSUM));
        var verifiedReceipt = paid;
        assertThat(verifiedReceipt.getDisposition()).isEqualTo("MATCHED");
        assertThat(payosPersistence.recordVerifiedWebhook(PayosProtocol.verifyWebhookEvidence(
                signedWebhook(identity.getOrderCode(), identity.getAmountVnd(), "link-test-1", "123456789", "00"),
                "test-merchant-channel", TEST_PAYOS_CHECKSUM)).getReceiptId())
                .isEqualTo(verifiedReceipt.getReceiptId());
        var concurrentEvidence = PayosProtocol.verifyWebhookEvidence(
                signedWebhook(identity.getOrderCode(), identity.getAmountVnd(), "link-test-1", "123456789", "00", "parallel"),
                "test-merchant-channel", TEST_PAYOS_CHECKSUM);
        CountDownLatch concurrentReady = new CountDownLatch(2);
        CountDownLatch concurrentStart = new CountDownLatch(1);
        ExecutorService receiptWorkers = Executors.newFixedThreadPool(2);
        try {
            List<Future<UUID>> receiptResults = List.of(0, 1).stream().map(index -> receiptWorkers.submit(() -> {
                concurrentReady.countDown(); concurrentStart.await();
                return payosPersistence.recordVerifiedWebhook(concurrentEvidence).getReceiptId();
            })).toList();
            concurrentReady.await(); concurrentStart.countDown();
            assertThat(receiptResults.get(0).get()).isEqualTo(receiptResults.get(1).get());
        } finally { receiptWorkers.shutdownNow(); }
        var pendingReceipt = payosPersistence.recordVerifiedWebhook(PayosProtocol.verifyWebhookEvidence(
                signedWebhook(identity.getOrderCode(), identity.getAmountVnd(), "link-test-1", "123456789", "PENDING"),
                "test-merchant-channel", TEST_PAYOS_CHECKSUM));
        assertThat(pendingReceipt.getDisposition()).isNotEqualTo("MATCHED");
        var mismatchedReceipt = payosPersistence.recordVerifiedWebhook(PayosProtocol.verifyWebhookEvidence(
                signedWebhook(identity.getOrderCode(), identity.getAmountVnd() + 1, "link-test-1", "wrong-account", "00"),
                "test-merchant-channel", TEST_PAYOS_CHECKSUM));
        assertThat(mismatchedReceipt.getDisposition()).isEqualTo("RECONCILIATION_REQUIRED");
        var wrongOrderReceipt = payosPersistence.recordVerifiedWebhook(PayosProtocol.verifyWebhookEvidence(
                signedWebhook(identity.getOrderCode() + 1, identity.getAmountVnd(), "link-test-1", "123456789", "00"),
                "test-merchant-channel", TEST_PAYOS_CHECKSUM));
        assertThat(wrongOrderReceipt.getDisposition()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(payosPersistence.recordVerifiedWebhook(PayosProtocol.verifyWebhookEvidence(
                signedWebhook(identity.getOrderCode(), identity.getAmountVnd(), "wrong-link-123", "123456789", "00"),
                "test-merchant-channel", TEST_PAYOS_CHECKSUM)).getDisposition()).isEqualTo("CONFLICT");
        assertThat(payosPersistence.recordVerifiedWebhook(PayosProtocol.verifyWebhookEvidence(
                signedWebhook(identity.getOrderCode(), identity.getAmountVnd(), "link-test-1", "123456789", "00"),
                "wrong-channel", TEST_PAYOS_CHECKSUM)).getDisposition()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(payosPersistence.recordVerifiedWebhook(PayosProtocol.verifyWebhookEvidence(
                signedWebhook(identity.getOrderCode(), identity.getAmountVnd(), "link-test-1", "123456789", "00", "USD", "currency"),
                "test-merchant-channel", TEST_PAYOS_CHECKSUM)).getDisposition()).isNotEqualTo("MATCHED");
        var query = PayosProtocol.verifyPaymentLinkQueryEvidence(signedQuery(identity.getOrderCode(), identity.getAmountVnd(),
                        "link-test-1", identity.getAmountVnd() - 1, "PAID"), "test-merchant-channel", "query-1",
                new PayosProtocol.ExpectedPayment(attemptId, identity.getOrderCode(), identity.getAmountVnd(),
                        "2DTO3D002", "link-test-1", "123456789"), TEST_PAYOS_CHECKSUM);
        assertThat(payosPersistence.storeReconciliationQuery(query).getOutcome()).isEqualTo("UNKNOWN");
        jdbc.update("UPDATE payos_payment_links SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE attempt_id = ?", attemptId);
        var expiredView = payosPersistence.readOwned(attemptId, fixture.ownerId());
        assertThat(expiredView.state()).isEqualTo("EXPIRED");
        assertThat(expiredView.checkoutUrl()).isNull();
        assertThat(expiredView.qrData()).isNull();
        assertThat(expiredView.recipientAccountNumber()).isNull();
        jdbc.update("UPDATE payos_payment_links SET link_state = 'UNKNOWN' WHERE attempt_id = ?", attemptId);
        var unknownView = payosPersistence.readOwned(attemptId, fixture.ownerId());
        assertThat(unknownView.state()).isEqualTo("UNKNOWN");
        assertThat(unknownView.checkoutUrl()).isNull();
        assertThat(payosPersistence.claimCreate(attemptId)).isFalse();
        assertThat(payosReceipts.findByAttemptIdOrderByReceivedAtDesc(attemptId)).hasSize(3);
    }

    @Test
    void ownerReadOnlyReturnsPayosSecretsForPendingAttemptWithLiveLink() {
        Fixture fixture = seedPayableOrder();
        var created = persistence.createOrReplay(fixture.ownerId(),
                new CreatePaymentAttemptRequest(fixture.orderId(), PaymentMethod.BANK_TRANSFER),
                "payos-owner-read-status", true);
        UUID attemptId = created.attempt().getAttemptId();
        var identity = prepareLiveTestLink(attemptId, "test-merchant-channel", "2DTO3D003",
                Instant.now().plus(Duration.ofMinutes(20)));
        assertThat(payosPersistence.claimCreate(attemptId)).isTrue();
        payosPersistence.recordCreated(attemptId, new PayosPaymentPersistenceService.CreatedLink(identity.getOrderCode(),
                identity.getAmountVnd(), "link-owner-read", "https://pay.payos.vn/read", "000201-QR-TEST",
                "9704", "123456789", "TEST ACCOUNT", identity.getExpiresAt(), "e".repeat(64)), Set.of("pay.payos.vn"));
        var pending = payosPersistence.readOwned(attemptId, fixture.ownerId());
        assertThat(pending.qrData()).isEqualTo("000201-QR-TEST");
        assertThat(pending.checkoutUrl()).isEqualTo("https://pay.payos.vn/read");

        for (String status : List.of("UNKNOWN", "SUCCEEDED", "FAILED", "CANCELED", "EXPIRED", "INITIATED", "SUBMITTING")) {
            jdbc.update("UPDATE payment_attempts SET status = ? WHERE attempt_id = ?", status, attemptId);
            var hidden = payosPersistence.readOwned(attemptId, fixture.ownerId());
            assertThat(hidden.qrData()).as("QR for %s", status).isNull();
            assertThat(hidden.checkoutUrl()).as("checkout URL for %s", status).isNull();
            assertThat(hidden.recipientAccountNumber()).as("recipient for %s", status).isNull();
        }
    }

    @Test
    void testOnlyPayosCreateWebhookSettlementAndOwnerReadAreIntegrated() throws Exception {
        Fixture fixture = seedPayableOrder();
        AtomicInteger creates = new AtomicInteger();
        PayosTransport transport = new PayosTransport() {
            @Override public PayosTransport.HttpResponse createPaymentLink(String endpoint, String clientId,
                    String apiKey, Map<String, Object> request, Duration timeout) {
                creates.incrementAndGet();
                return new PayosTransport.HttpResponse(200, signedCreateResponse(request));
            }
            @Override public PayosTransport.HttpResponse getPaymentLink(String endpoint, String clientId,
                    String apiKey, long orderCode, Duration timeout) {
                throw new AssertionError("TEST_ONLY create must not query payOS");
            }
        };
        PayosGateway provider = new PayosGateway("https://api-merchant.payos.vn", "TEST_ONLY_CLIENT",
                "TEST_ONLY_API_KEY", TEST_PAYOS_CHECKSUM, Set.of("pay.payos.vn"), Duration.ofSeconds(1), transport);
        PayosTestOnlyPaymentAttemptGateway gateway = new PayosTestOnlyPaymentAttemptGateway(true,
                "test-merchant-channel", "https://shop.example/cancel", "https://shop.example/return",
                Set.of("pay.payos.vn"), Set.of("shop.example"), Duration.ofMinutes(20), provider,
                payosPersistence, java.time.Clock.systemUTC());
        PaymentServiceImpl paymentService = new PaymentServiceImpl(featureGate, readiness, persistence, lifecycle,
                List.of(gateway), payosPersistence, payosWebhookProcessing);
        MockMvc http = MockMvcBuilders.standaloneSetup(new PaymentController(paymentService, featureGate))
                .setControllerAdvice(new PaymentApiExceptionHandler()).build();
        Authentication owner = authenticationFor(fixture.ownerId());

        var created = http.perform(post("/api/payments/attempts").principal(owner)
                        .header("Idempotency-Key", "payos-test-only-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":" + fixture.orderId() + ",\"method\":\"BANK_TRANSFER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.payosPayment.provenance").value("TEST_ONLY"))
                .andExpect(jsonPath("$.payosPayment.testOnly").value(true))
                .andExpect(jsonPath("$.payosPayment.qrPayload").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.payosPayment.checkoutUrl").value(org.hamcrest.Matchers.nullValue()))
                .andReturn();
        UUID attemptId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(
                created.getResponse().getContentAsString(), "$.attemptId"));
        var link = payosLinks.findById(attemptId).orElseThrow();
        assertThat(link.getProvenance().name()).isEqualTo("TEST_ONLY");
        PaymentServiceImpl afterGatewayChange = new PaymentServiceImpl(featureGate, readiness, persistence, lifecycle,
                List.of(), payosPersistence, payosWebhookProcessing);
        MockMvc reconfiguredHttp = MockMvcBuilders.standaloneSetup(new PaymentController(afterGatewayChange, featureGate))
                .setControllerAdvice(new PaymentApiExceptionHandler()).build();
        reconfiguredHttp.perform(get("/api/payments/attempts/" + attemptId).principal(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payosPayment.provenance").value("TEST_ONLY"))
                .andExpect(jsonPath("$.payosPayment.testOnly").value(true))
                .andExpect(jsonPath("$.payosPayment.qrPayload").value(org.hamcrest.Matchers.nullValue()));
        String webhook = signedPayosWebhook(link.getOrderCode(), link.getAmountVnd(), link.getPaymentLinkId(),
                "987654321", link.getDescription(), "00", "bank-reference-test-only");
        var settled = payosWebhookProcessing.process("test-merchant-channel", webhook);
        var duplicate = payosWebhookProcessing.process("test-merchant-channel", webhook);

        assertThat(settled.disposition()).isEqualTo("MATCHED");
        assertThat(settled.paymentStatus()).isEqualTo(com.exe.morphia3d.domain.payment.PaymentAttemptStatus.SUCCEEDED);
        assertThat(duplicate.paymentStatus()).isEqualTo(com.exe.morphia3d.domain.payment.PaymentAttemptStatus.SUCCEEDED);
        assertThat(creates).hasValue(1);
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE order_id = ?", String.class,
                fixture.orderId())).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE order_id = ?", String.class,
                fixture.orderId())).isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_callback_events WHERE attempt_id = ?",
                Integer.class, attemptId)).isEqualTo(1);
        http.perform(get("/api/payments/attempts/" + attemptId).principal(owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.payosPayment").doesNotExist());
        http.perform(get("/api/payments/attempts/" + attemptId)
                        .principal(authenticationFor(fixture.ownerId() + 1000)))
                .andExpect(status().isNotFound());
        http.perform(post("/api/payments/payos/webhook").principal(owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SUCCEEDED\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void paidWebhookBeforeCreateResponseSettlesFromDurableReceiptAfterLinkRecovery() {
        Fixture fixture = seedPayableOrder();
        var creation = persistence.createOrReplay(fixture.ownerId(),
                new CreatePaymentAttemptRequest(fixture.orderId(), PaymentMethod.BANK_TRANSFER),
                "payos-early-webhook", true);
        UUID attemptId = creation.attempt().getAttemptId();
        String compact = attemptId.toString().replace("-", "").toUpperCase();
        String description = "2D" + compact.substring(compact.length() - 7);
        var reserved = payosPersistence.prepare(attemptId, "test-merchant-channel", description,
                Instant.now().plus(Duration.ofMinutes(20)));
        assertThat(payosPersistence.claimCreate(attemptId)).isTrue();
        String webhook = signedPayosWebhook(reserved.getOrderCode(), reserved.getAmountVnd(), "link-early-response",
                "987654321", description, "00", "bank-reference-early");
        assertThat(payosWebhookProcessing.process("test-merchant-channel", webhook).disposition())
                .isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE order_id = ?", String.class,
                fixture.orderId())).isEqualTo("PENDING");

        payosPersistence.recordCreated(attemptId, new PayosPaymentPersistenceService.CreatedLink(
                reserved.getOrderCode(), reserved.getAmountVnd(), "link-early-response",
                "https://pay.payos.vn/early-link", "QR-EARLY", "970422", "987654321", "TEST ACCOUNT",
                reserved.getExpiresAt(), "b".repeat(64)), Set.of("pay.payos.vn"));
        assertThat(payosWebhookProcessing.applyDurableMatchedReceipts(attemptId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE order_id = ?", String.class,
                fixture.orderId())).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE order_id = ?", String.class,
                fixture.orderId())).isEqualTo("PROCESSING");
    }

    @Test
    void verifiedReconciliationRequiresExactPaidAmountAndNeverRetriesCreate() {
        Fixture fixture = seedPayableOrder();
        AtomicInteger creates = new AtomicInteger();
        AtomicReference<Long> orderCode = new AtomicReference<>();
        AtomicReference<Long> amount = new AtomicReference<>();
        AtomicReference<String> linkId = new AtomicReference<>();
        AtomicReference<Long> amountPaid = new AtomicReference<>();
        PayosTransport transport = new PayosTransport() {
            @Override public PayosTransport.HttpResponse createPaymentLink(String endpoint, String clientId,
                    String apiKey, Map<String, Object> request, Duration timeout) {
                creates.incrementAndGet();
                orderCode.set(((Number) request.get("orderCode")).longValue());
                amount.set(((Number) request.get("amount")).longValue());
                linkId.set("link-query-" + orderCode.get());
                amountPaid.set(amount.get() - 1);
                return new PayosTransport.HttpResponse(200, signedCreateResponse(request, linkId.get()));
            }
            @Override public PayosTransport.HttpResponse getPaymentLink(String endpoint, String clientId,
                    String apiKey, long requestedOrderCode, Duration timeout) {
                return new PayosTransport.HttpResponse(200, signedQueryResponse(orderCode.get(), amount.get(),
                        amountPaid.get(), linkId.get(), "PAID"));
            }
        };
        PayosGateway provider = new PayosGateway("https://api-merchant.payos.vn", "TEST_ONLY_CLIENT",
                "TEST_ONLY_API_KEY", TEST_PAYOS_CHECKSUM, Set.of("pay.payos.vn"), Duration.ofSeconds(1), transport);
        PayosTestOnlyPaymentAttemptGateway gateway = new PayosTestOnlyPaymentAttemptGateway(true,
                "test-merchant-channel", "https://shop.example/cancel", "https://shop.example/return",
                Set.of("pay.payos.vn"), Set.of("shop.example"), Duration.ofMinutes(20), provider,
                payosPersistence, java.time.Clock.systemUTC());
        PaymentServiceImpl paymentService = new PaymentServiceImpl(featureGate, readiness, persistence, lifecycle,
                List.of(gateway), payosPersistence, payosWebhookProcessing);
        PaymentAttemptResponse created = paymentService.createAttempt(fixture.ownerId(),
                new CreatePaymentAttemptRequest(fixture.orderId(), PaymentMethod.BANK_TRANSFER), "query-attempt-key");
        jdbc.update("UPDATE payment_attempts SET status = 'UNKNOWN', unknown_since = CURRENT_TIMESTAMP WHERE attempt_id = ?",
                created.attemptId());
        PayosReconciliationProcessingService reconciler = new PayosReconciliationProcessingService(
                Map.of("test-merchant-channel", provider), payosPersistence, persistence, lifecycle);

        var underpaid = reconciler.reconcileUnknown(created.attemptId(), "test-merchant-channel");
        assertThat(underpaid.status()).isEqualTo(com.exe.morphia3d.domain.payment.PaymentAttemptStatus.UNKNOWN);
        assertThat(underpaid.resolved()).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE order_id = ?", String.class,
                fixture.orderId())).isEqualTo("PENDING");

        amountPaid.set(amount.get() + 1);
        var overpaid = reconciler.reconcileUnknown(created.attemptId(), "test-merchant-channel");
        assertThat(overpaid.status()).isEqualTo(com.exe.morphia3d.domain.payment.PaymentAttemptStatus.UNKNOWN);
        assertThat(overpaid.resolved()).isFalse();
        amountPaid.set(amount.get());
        var paid = reconciler.reconcileUnknown(created.attemptId(), "test-merchant-channel");
        assertThat(paid.status()).isEqualTo(com.exe.morphia3d.domain.payment.PaymentAttemptStatus.SUCCEEDED);
        assertThat(paid.resolved()).isTrue();
        assertThat(creates).hasValue(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payos_reconciliation_queries WHERE attempt_id = ?",
                Integer.class, created.attemptId())).isEqualTo(3);
        var link = payosLinks.findById(created.attemptId()).orElseThrow();
        var lateWebhook = payosWebhookProcessing.process("test-merchant-channel", signedPayosWebhook(
                link.getOrderCode(), link.getAmountVnd(), link.getPaymentLinkId(), "987654321", link.getDescription(),
                "00", "late-after-query"));
        assertThat(lateWebhook.paymentStatus()).isEqualTo(com.exe.morphia3d.domain.payment.PaymentAttemptStatus.SUCCEEDED);
        assertThat(payosReceipts.findByMerchantChannelIdAndBankReferenceOrderByReceivedAtAsc(
                "test-merchant-channel", "late-after-query")).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_reconciliation_cases WHERE attempt_id = ?",
                Integer.class, created.attemptId())).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE order_id = ?", String.class,
                fixture.orderId())).isEqualTo("PROCESSING");
    }

    @Test
    void successfulWebhookThenLatePaidQueryKeepsOneSettlementAndDoesNotConflictReferences() {
        Fixture fixture = seedPayableOrder();
        AtomicReference<Long> orderCode = new AtomicReference<>();
        AtomicReference<Long> amount = new AtomicReference<>();
        AtomicReference<String> linkId = new AtomicReference<>();
        PayosTransport transport = new PayosTransport() {
            @Override public PayosTransport.HttpResponse createPaymentLink(String endpoint, String clientId,
                    String apiKey, Map<String, Object> request, Duration timeout) {
                orderCode.set(((Number) request.get("orderCode")).longValue());
                amount.set(((Number) request.get("amount")).longValue());
                linkId.set("link-webhook-then-query");
                return new PayosTransport.HttpResponse(200, signedCreateResponse(request, linkId.get()));
            }
            @Override public PayosTransport.HttpResponse getPaymentLink(String endpoint, String clientId,
                    String apiKey, long requestedOrderCode, Duration timeout) {
                return new PayosTransport.HttpResponse(200, signedQueryResponse(orderCode.get(), amount.get(),
                        amount.get(), linkId.get(), "PAID"));
            }
        };
        PayosGateway provider = new PayosGateway("https://api-merchant.payos.vn", "TEST_ONLY_CLIENT",
                "TEST_ONLY_API_KEY", TEST_PAYOS_CHECKSUM, Set.of("pay.payos.vn"), Duration.ofSeconds(1), transport);
        PayosTestOnlyPaymentAttemptGateway gateway = new PayosTestOnlyPaymentAttemptGateway(true,
                "test-merchant-channel", "https://shop.example/cancel", "https://shop.example/return",
                Set.of("pay.payos.vn"), Set.of("shop.example"), Duration.ofMinutes(20), provider,
                payosPersistence, java.time.Clock.systemUTC());
        PaymentServiceImpl paymentService = new PaymentServiceImpl(featureGate, readiness, persistence, lifecycle,
                List.of(gateway), payosPersistence, payosWebhookProcessing);
        PaymentAttemptResponse created = paymentService.createAttempt(fixture.ownerId(),
                new CreatePaymentAttemptRequest(fixture.orderId(), PaymentMethod.BANK_TRANSFER), "webhook-then-query-key");
        var link = payosLinks.findById(created.attemptId()).orElseThrow();
        String bankReference = "bank-reference-webhook-then-query";
        assertThat(payosWebhookProcessing.process("test-merchant-channel", signedPayosWebhook(
                link.getOrderCode(), link.getAmountVnd(), link.getPaymentLinkId(), "987654321",
                link.getDescription(), "00", bankReference)).paymentStatus())
                .isEqualTo(com.exe.morphia3d.domain.payment.PaymentAttemptStatus.SUCCEEDED);

        PayosReconciliationProcessingService reconciler = new PayosReconciliationProcessingService(
                Map.of("test-merchant-channel", provider), payosPersistence, persistence, lifecycle);
        var lateQuery = reconciler.reconcileUnknown(created.attemptId(), "test-merchant-channel");

        assertThat(lateQuery.status()).isEqualTo(com.exe.morphia3d.domain.payment.PaymentAttemptStatus.SUCCEEDED);
        assertThat(lateQuery.resolved()).isTrue();
        assertThat(attempts.findById(created.attemptId()).orElseThrow().getProviderTransactionId()).isEqualTo(bankReference);
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE order_id = ?", String.class,
                fixture.orderId())).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE order_id = ?", String.class,
                fixture.orderId())).isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_reconciliation_cases WHERE attempt_id = ?",
                Integer.class, created.attemptId())).isZero();
    }

    @Test
    void uncertainCreateOutcomeIsNotResentOnIdempotentReplay() {
        Fixture fixture = seedPayableOrder();
        AtomicInteger creates = new AtomicInteger();
        PayosTransport timeoutTransport = new PayosTransport() {
            @Override public PayosTransport.HttpResponse createPaymentLink(String endpoint, String clientId,
                    String apiKey, Map<String, Object> request, Duration timeout) {
                creates.incrementAndGet();
                throw new IllegalStateException("TEST_ONLY response lost after request write");
            }
            @Override public PayosTransport.HttpResponse getPaymentLink(String endpoint, String clientId,
                    String apiKey, long orderCode, Duration timeout) {
                return null;
            }
        };
        PayosGateway provider = new PayosGateway("https://api-merchant.payos.vn", "TEST_ONLY_CLIENT",
                "TEST_ONLY_API_KEY", TEST_PAYOS_CHECKSUM, Set.of("pay.payos.vn"), Duration.ofSeconds(1), timeoutTransport);
        PayosTestOnlyPaymentAttemptGateway gateway = new PayosTestOnlyPaymentAttemptGateway(true,
                "test-merchant-channel", "https://shop.example/cancel", "https://shop.example/return",
                Set.of("pay.payos.vn"), Set.of("shop.example"), Duration.ofMinutes(20), provider,
                payosPersistence, java.time.Clock.systemUTC());
        PaymentServiceImpl paymentService = new PaymentServiceImpl(featureGate, readiness, persistence, lifecycle,
                List.of(gateway), payosPersistence, payosWebhookProcessing);
        CreatePaymentAttemptRequest request = new CreatePaymentAttemptRequest(fixture.orderId(), PaymentMethod.BANK_TRANSFER);

        var first = paymentService.createAttempt(fixture.ownerId(), request, "unknown-create-same-key");
        var replay = paymentService.createAttempt(fixture.ownerId(), request, "unknown-create-same-key");

        assertThat(first.attemptId()).isEqualTo(replay.attemptId());
        assertThat(first.status()).isEqualTo(com.exe.morphia3d.domain.payment.PaymentAttemptStatus.UNKNOWN);
        assertThat(replay.status()).isEqualTo(com.exe.morphia3d.domain.payment.PaymentAttemptStatus.UNKNOWN);
        assertThat(creates).hasValue(1);
        assertThat(payosLinks.findById(first.attemptId()).orElseThrow().getLinkState()).isEqualTo("UNKNOWN");
        assertThatThrownBy(() -> paymentService.createAttempt(fixture.ownerId(), request, "unknown-create-new-key"))
                .isInstanceOf(PaymentApiException.class).extracting("status").isEqualTo(409);
        assertThat(creates).hasValue(1);
    }

    @Test
    void webhookReceiptAndSettlementRollbackTogetherWhenPaymentUpdateCrashes() {
        Fixture fixture = seedPayableOrder();
        var creation = persistence.createOrReplay(fixture.ownerId(),
                new CreatePaymentAttemptRequest(fixture.orderId(), PaymentMethod.BANK_TRANSFER),
                "payos-crash-boundary", true);
        UUID attemptId = creation.attempt().getAttemptId();
        String compact = attemptId.toString().replace("-", "").toUpperCase();
        String description = "2D" + compact.substring(compact.length() - 7);
        var reserved = payosPersistence.prepare(attemptId, "test-merchant-channel", description,
                Instant.now().plus(Duration.ofMinutes(20)));
        assertThat(payosPersistence.claimCreate(attemptId)).isTrue();
        String linkId = "link-crash-boundary";
        payosPersistence.recordCreated(attemptId, new PayosPaymentPersistenceService.CreatedLink(
                reserved.getOrderCode(), reserved.getAmountVnd(), linkId, "https://pay.payos.vn/crash",
                "QR-CRASH", "970422", "987654321", "TEST ACCOUNT", reserved.getExpiresAt(), "c".repeat(64)),
                Set.of("pay.payos.vn"));
        String webhook = signedPayosWebhook(reserved.getOrderCode(), reserved.getAmountVnd(), linkId,
                "987654321", description, "00", "bank-reference-crash");
        jdbc.execute("CREATE FUNCTION fail_payment_success_for_test() RETURNS trigger LANGUAGE plpgsql AS $$ " +
                "BEGIN IF NEW.status = 'SUCCESS' THEN RAISE EXCEPTION 'TEST_ONLY crash'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER fail_payment_success_for_test BEFORE UPDATE ON payments " +
                "FOR EACH ROW EXECUTE FUNCTION fail_payment_success_for_test()");
        try {
            assertThatThrownBy(() -> payosWebhookProcessing.process("test-merchant-channel", webhook))
                    .isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payos_webhook_receipts WHERE order_code = ?",
                    Integer.class, reserved.getOrderCode())).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_callback_events WHERE attempt_id = ?",
                    Integer.class, attemptId)).isZero();
            assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE order_id = ?", String.class,
                    fixture.orderId())).isEqualTo("PENDING");
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS fail_payment_success_for_test ON payments");
            jdbc.execute("DROP FUNCTION IF EXISTS fail_payment_success_for_test()");
        }
        assertThat(payosWebhookProcessing.process("test-merchant-channel", webhook).paymentStatus())
                .isEqualTo(com.exe.morphia3d.domain.payment.PaymentAttemptStatus.SUCCEEDED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payos_webhook_receipts WHERE order_code = ?",
                Integer.class, reserved.getOrderCode())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE order_id = ?", String.class,
                fixture.orderId())).isEqualTo("PROCESSING");
    }

    private static final String TEST_PAYOS_CHECKSUM = "1a54716c8f0efb2744fb28b6e38b25da7f67a925d98bc1c18bd8faaecadd7675";
    private String signedWebhook(long orderCode, long amount, String linkId, String account, String code) {
        return signedWebhook(orderCode, amount, linkId, account, code, "");
    }
    private String signedWebhook(long orderCode, long amount, String linkId, String account, String code, String suffix) {
        return signedWebhook(orderCode, amount, linkId, account, code, "VND", suffix);
    }
    private String signedWebhook(long orderCode, long amount, String linkId, String account, String code, String currency, String suffix) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderCode", orderCode); data.put("amount", amount); data.put("description", "2DTO3D002");
        data.put("accountNumber", account); data.put("reference", "bank-ref-" + orderCode + "-" + amount + "-" + code + suffix);
        data.put("transactionDateTime", "2026-11-13 00:00:00"); data.put("currency", currency);
        data.put("paymentLinkId", linkId); data.put("code", code); data.put("desc", "test");
        return "{\"code\":\"00\",\"desc\":\"success\",\"success\":true,\"data\":"
                + new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(data)
                + ",\"signature\":\"" + PayosProtocol.signData(data, TEST_PAYOS_CHECKSUM) + "\"}";
    }
    private String signedQuery(long orderCode, long amount, String linkId, long paid, String status) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderCode", orderCode); data.put("amount", amount); data.put("amountPaid", paid);
        data.put("amountRemaining", Math.max(0, amount - paid)); data.put("status", status); data.put("id", linkId);
        return "{\"code\":\"00\",\"desc\":\"success\",\"data\":"
                + new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(data)
                + ",\"signature\":\"" + PayosProtocol.signData(data, TEST_PAYOS_CHECKSUM) + "\"}";
    }

    private String signedCreateResponse(Map<String, Object> request) {
        return signedCreateResponse(request, "link-test-" + request.get("orderCode"));
    }

    private String signedCreateResponse(Map<String, Object> request, String paymentLinkId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("bin", "970422"); data.put("accountNumber", "987654321"); data.put("accountName", "TEST ACCOUNT");
        data.put("amount", request.get("amount")); data.put("description", request.get("description"));
        data.put("orderCode", request.get("orderCode")); data.put("currency", "VND");
        data.put("paymentLinkId", paymentLinkId); data.put("status", "PENDING");
        data.put("checkoutUrl", "https://pay.payos.vn/test-link"); data.put("qrCode", "QR-TEST-ONLY");
        return "{\"code\":\"00\",\"desc\":\"success\",\"data\":"
                + new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(data)
                + ",\"signature\":\"" + PayosProtocol.signData(data, TEST_PAYOS_CHECKSUM) + "\"}";
    }

    private String signedQueryResponse(long orderCode, long amount, long amountPaid, String linkId, String status) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderCode", orderCode); data.put("amount", amount); data.put("amountPaid", amountPaid);
        data.put("amountRemaining", Math.max(0L, amount - amountPaid)); data.put("status", status); data.put("id", linkId);
        return "{\"code\":\"00\",\"desc\":\"success\",\"data\":"
                + new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(data)
                + ",\"signature\":\"" + PayosProtocol.signData(data, TEST_PAYOS_CHECKSUM) + "\"}";
    }

    private String signedPayosWebhook(long orderCode, long amount, String linkId, String account,
            String description, String code, String bankReference) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderCode", orderCode); data.put("amount", amount); data.put("description", description);
        data.put("accountNumber", account); data.put("reference", bankReference);
        data.put("transactionDateTime", "2026-11-13 00:00:00"); data.put("currency", "VND");
        data.put("paymentLinkId", linkId); data.put("code", code); data.put("desc", "test");
        return "{\"code\":\"00\",\"desc\":\"success\",\"success\":true,\"data\":"
                + new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(data)
                + ",\"signature\":\"" + PayosProtocol.signData(data, TEST_PAYOS_CHECKSUM) + "\"}";
    }

    private String applyAtBarrier(VerifiedPaymentEvent event, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        return lifecycle.applyVerifiedEvent(event).getStatus().name();
    }

    @Test
    void mockHttpCheckoutUsesBackendReadinessAndScopesAttemptLookupToAuthenticatedOwner() throws Exception {
        Fixture fixture = seedPayableOrder();
        TestOnlyGateway gateway = new TestOnlyGateway();
        PaymentServiceImpl paymentService = new PaymentServiceImpl(featureGate, readiness, persistence, lifecycle,
                List.of(gateway));
        MockMvc http = MockMvcBuilders.standaloneSetup(new PaymentController(paymentService, featureGate))
                .setControllerAdvice(new PaymentApiExceptionHandler())
                .build();
        Authentication owner = authenticationFor(fixture.ownerId());

        jdbc.update("UPDATE order_items SET price_snapshot_policy_version = 'unapproved-test' WHERE order_id = ?",
                fixture.orderId());
        http.perform(get("/api/payments/readiness").param("orderId", fixture.orderId().toString())
                        .principal(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(false))
                .andExpect(jsonPath("$.blocker").value("COMMERCIAL_PRICING_UNAPPROVED"))
                .andExpect(jsonPath("$.shippingConfirmed").value(true))
                .andExpect(jsonPath("$.totalFrozen").value(true));
        http.perform(post("/api/payments/attempts")
                        .principal(owner)
                        .header("Idempotency-Key", "http-payment-test-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":" + fixture.orderId() + ",\"method\":\"BANK_TRANSFER\"}"))
                .andExpect(status().isConflict());
        assertThat(gateway.submissions()).isZero();

        jdbc.update("UPDATE order_items SET price_snapshot_policy_version = 'payment-test-approved' WHERE order_id = ?",
                fixture.orderId());
        var created = http.perform(post("/api/payments/attempts")
                        .principal(owner)
                        .header("Idempotency-Key", "http-payment-test-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":" + fixture.orderId() + ",\"method\":\"BANK_TRANSFER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();
        String attemptId = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.attemptId");
        http.perform(get("/api/payments/attempts/" + attemptId)
                        .principal(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
        http.perform(get("/api/payments/attempts/" + attemptId)
                        .principal(authenticationFor(fixture.ownerId() + 1)))
                .andExpect(status().isNotFound());
        assertThat(gateway.submissions()).isEqualTo(1);

        Fixture timeoutFixture = seedPayableOrder();
        gateway.next(SubmissionBehavior.TIMEOUT);
        var unknown = http.perform(post("/api/payments/attempts")
                        .principal(authenticationFor(timeoutFixture.ownerId()))
                        .header("Idempotency-Key", "http-timeout-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":" + timeoutFixture.orderId() + ",\"method\":\"BANK_TRANSFER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNKNOWN"))
                .andReturn();
        assertThat(unknown.getResponse().getContentAsString()).contains("UNKNOWN");
        http.perform(post("/api/payments/attempts")
                        .principal(authenticationFor(timeoutFixture.ownerId()))
                        .header("Idempotency-Key", "http-timeout-retry-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":" + timeoutFixture.orderId() + ",\"method\":\"BANK_TRANSFER\"}"))
                .andExpect(status().isConflict());

        Fixture failedFixture = seedPayableOrder();
        gateway.next(SubmissionBehavior.REJECTED);
        http.perform(post("/api/payments/attempts")
                        .principal(authenticationFor(failedFixture.ownerId()))
                        .header("Idempotency-Key", "http-failed-first-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":" + failedFixture.orderId() + ",\"method\":\"BANK_TRANSFER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
        http.perform(post("/api/payments/attempts")
                        .principal(authenticationFor(failedFixture.ownerId()))
                        .header("Idempotency-Key", "http-failed-retry-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":" + failedFixture.orderId() + ",\"method\":\"BANK_TRANSFER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    private Authentication authenticationFor(Integer ownerId) {
        UserPrincipal principal = new UserPrincipal(User.builder().id(ownerId).build());
        return new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private void assertRetryBlocked(PaymentServiceImpl paymentService, Fixture fixture, String key) {
        assertThatThrownBy(() -> paymentService.createAttempt(fixture.ownerId(),
                new CreatePaymentAttemptRequest(fixture.orderId(), PaymentMethod.BANK_TRANSFER), key))
                .isInstanceOf(PaymentApiException.class)
                .extracting("status").isEqualTo(409);
    }

    private PaymentAttemptResponse createAtBarrier(PaymentServiceImpl paymentService, Fixture fixture,
                                                   CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        start.await();
        return paymentService.createAttempt(fixture.ownerId(),
                new CreatePaymentAttemptRequest(fixture.orderId(), PaymentMethod.BANK_TRANSFER), "concurrent-same-key");
    }

    /** Creates an isolated verified binding and immutable LIVE provenance through V13's trusted function. */
    private com.exe.morphia3d.entity.PayosPaymentLink prepareLiveTestLink(UUID attemptId, String channel,
                                                                          String description, Instant expiresAt) {
        UUID bindingId = UUID.randomUUID();
        int bindingVersion = TEST_MERCHANT_BINDING_VERSION.incrementAndGet();
        String recipientFingerprint = "f".repeat(64);
        jdbc.queryForObject("SELECT payos_register_merchant_binding(?,?,?,?,?,?,?,?,?,?)", UUID.class,
                bindingId, channel, bindingVersion, "TEST_ONLY_CLIENT_ID", "secret://test/api-key/v1",
                "secret://test/checksum/v1", "secret://test/version/v" + bindingVersion, "970422",
                recipientFingerprint, "TEST_ONLY merchant evidence");
        jdbc.execute("SELECT payos_transition_merchant_binding('" + bindingId + "','VERIFIED','TEST_ONLY verification')");
        long amountVnd = attempts.findById(attemptId).orElseThrow().getAmountVnd();
        jdbc.queryForObject("SELECT payos_create_live_payment_link(?,?,?,?,?,?,?,?,?)", UUID.class,
                attemptId, channel, amountVnd, "a".repeat(64), description, java.sql.Timestamp.from(expiresAt),
                bindingId, bindingVersion, recipientFingerprint);
        return payosLinks.findById(attemptId).orElseThrow();
    }

    private Fixture seedPayableOrder() {
        String token = UUID.randomUUID().toString();
        Integer ownerId = jdbc.queryForObject("""
                INSERT INTO users(name, email, password, role, status)
                VALUES ('Payment test', ?, 'test-only', 'USER', 'ACTIVE') RETURNING user_id
                """, Integer.class, "payment-service-" + token + "@example.invalid");
        Integer printerId = jdbc.queryForObject("""
                INSERT INTO printers(name, status) VALUES (?, 'ACTIVE') RETURNING printer_id
                """, Integer.class, "Payment test printer " + token);
        Integer materialId = jdbc.queryForObject("""
                INSERT INTO materials(name, price_per_gram, stock_quantity, unit, is_available)
                VALUES (?, 1, 100, 'g', 'AVAILABLE') RETURNING material_id
                """, Integer.class, "Payment test material " + token);
        Integer modelId = jdbc.queryForObject("""
                INSERT INTO model_3d(source_type, user_id, name, model_status)
                VALUES ('DESIGNER_MODEL', ?, ?, 'ACTIVE') RETURNING model_id
                """, Integer.class, ownerId, "Payment test model " + token);
        Integer estimateId = jdbc.queryForObject("""
                INSERT INTO price_estimations(model_id, material_id, printer_id, total_price,
                    total_price_vnd, policy_version, pricing_policy_snapshot)
                VALUES (?, ?, ?, 125000, 125000, 'payment-test-approved', '{}') RETURNING estimate_id
                """, Integer.class, modelId, materialId, printerId);
        Integer orderId = jdbc.queryForObject("""
                INSERT INTO orders(user_id, printer_id, total_amount, total_amount_vnd, status,
                    shipping_amount_vnd, shipping_confirmation_reference, payable_amount_vnd, payment_total_frozen_at)
                VALUES (?, ?, 125000, 125000, 'PENDING', 0, 'test-fixture-shipping-confirmation',
                    125000, CURRENT_TIMESTAMP) RETURNING order_id
                """, Integer.class, ownerId, printerId);
        jdbc.update("""
                INSERT INTO order_items(order_id, model_id, estimate_id, quantity, price_snapshot,
                    price_snapshot_vnd, price_snapshot_policy_version, pricing_policy_snapshot)
                VALUES (?, ?, ?, 1, 125000, 125000, 'payment-test-approved', '{}')
                """, orderId, modelId, estimateId);
        return new Fixture(ownerId, orderId);
    }

    private record Fixture(Integer ownerId, Integer orderId) { }

    private static final class TestOnlyGateway implements PaymentAttemptGateway {
        private final AtomicInteger submissions = new AtomicInteger();
        private final AtomicReference<SubmissionBehavior> nextBehavior = new AtomicReference<>(SubmissionBehavior.ACCEPTED);

        @Override public PaymentProvider provider() { return PaymentProvider.PAYOS; }
        @Override public boolean isAvailable() { return true; }
        @Override public PaymentProviderSubmissionResult submit(PaymentProviderRequest request) {
            submissions.incrementAndGet();
            SubmissionBehavior behavior = nextBehavior.getAndSet(SubmissionBehavior.ACCEPTED);
            if (behavior == SubmissionBehavior.TIMEOUT) throw new IllegalStateException("TEST_ONLY timeout");
            if (behavior == SubmissionBehavior.REJECTED) {
                return new PaymentProviderSubmissionResult(PaymentProviderSubmissionResult.Outcome.REJECTED,
                        null, null, null, "TEST_ONLY_REJECTED", "Test-only rejection");
            }
            return new PaymentProviderSubmissionResult(PaymentProviderSubmissionResult.Outcome.ACCEPTED,
                    "test-only-request-" + request.attemptId(), null, null, null, null);
        }
        void next(SubmissionBehavior behavior) { nextBehavior.set(behavior); }
        int submissions() { return submissions.get(); }
    }

    private enum SubmissionBehavior { ACCEPTED, REJECTED, TIMEOUT }
}
