package com.exe.morphia3d.e2e;

import com.exe.morphia3d.Morphia3dApplication;
import com.exe.morphia3d.config.PayosFinalAcceptanceConfiguration;
import com.exe.morphia3d.entity.PayosPaymentLink;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.Role;
import com.exe.morphia3d.enums.UserStatus;
import com.exe.morphia3d.repository.PayosPaymentLinkRepository;
import com.exe.morphia3d.repository.UserRepository;
import com.exe.morphia3d.service.PayosWebhookProcessingService;
import com.exe.morphia3d.service.PayosReconciliationProcessingService;
import com.exe.morphia3d.service.payment.payos.PayosPaymentPersistenceService;
import com.exe.morphia3d.service.payment.payos.PayosProtocol;
import com.exe.morphia3d.test.support.PostgresTestRoleProvisioning;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.context.annotation.Import;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Full FE/browser → Spring Boot → disposable PostgreSQL acceptance using an offline fake payOS transport. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = Morphia3dApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.flyway.enabled=true",
                "spring.flyway.validate-on-migrate=true",
                "app.features.cart.enabled=true",
                "app.features.order.enabled=true",
                "app.features.checkout.enabled=true",
                "app.features.payment.enabled=true",
                "app.pricing.approved-commercial-policy-versions=payment-test-approved",
                "app.payment.payos.webhook-checksum-keys.test-merchant-channel=" + PayosFinalAcceptanceConfiguration.CHECKSUM,
                "app.payment.payos.encryption-keyring[1]=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                "app.payment.payos.encryption-key-version=1"
        })
@ActiveProfiles("test")
@Import(PayosFinalAcceptanceConfiguration.class)
class PayosFinalAcceptanceBrowserE2E {
    private static final String DATABASE = "payos_final_acceptance";
    private static final String PASSWORD = "TEST_ONLY_acceptance_password_2026";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName(DATABASE)
            .withUsername("payos_final_acceptance")
            .withPassword("TEST_ONLY_DATABASE_PASSWORD");

    @DynamicPropertySource
    static void configureIsolatedPostgres(DynamicPropertyRegistry registry) {
        if (!POSTGRES.isRunning()) POSTGRES.start();
        String jdbcUrl = POSTGRES.getJdbcUrl();
        JdbcTemplate bootstrap = new JdbcTemplate(new DriverManagerDataSource(
                jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword()));
        PostgresTestRoleProvisioning.provisionMigrationRoles(bootstrap, POSTGRES.getUsername());
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .schemas("public")
                .defaultSchema("public")
                .cleanDisabled(true)
                .target(MigrationVersion.fromVersion("14"))
                .load()
                .migrate();
        PostgresTestRoleProvisioning.grantSnapshotReferencePrivileges(bootstrap);
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .schemas("public")
                .defaultSchema("public")
                .cleanDisabled(true)
                .validateOnMigrate(true)
                .load();
        flyway.migrate();
        flyway.validate();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @LocalServerPort private int backendPort;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private PayosPaymentLinkRepository links;
    @Autowired private PayosWebhookProcessingService webhookProcessor;
    @Autowired private AtomicInteger payosCreateCalls;
    @Autowired private ApplicationContext context;

    @Test
    void checkoutOrderPaymentWebhookAndOwnerScopedSuccessRunAgainstRealBackend() throws Exception {
        assertThat(context.getBeansOfType(PayosPaymentPersistenceService.class)).hasSize(1);
        assertThat(context.getBeansOfType(PayosWebhookProcessingService.class)).hasSize(1);
        assertThat(context.getBeansOfType(PayosReconciliationProcessingService.class)).hasSize(1);
        String runId = UUID.randomUUID().toString();
        String ownerEmail = "payos-owner-" + runId + "@example.invalid";
        String otherEmail = "payos-other-" + runId + "@example.invalid";
        Integer ownerId = seedCustomerWithBackendCart(ownerEmail, "TEST_ONLY payment owner " + runId);
        seedCustomerWithBackendCart(otherEmail, "TEST_ONLY other customer " + runId);
        installTestOnlyShippingConfirmationFixture();

        Path backendRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path frontend = backendRoot.resolve("../2Dto3D_FE").normalize();
        assertThat(frontend.resolve("node_modules/vite/bin/vite.js")).exists();
        assertThat(frontend.resolve("node_modules/@playwright/test/cli.js")).exists();
        Path evidence = backendRoot.resolve(".mvn-target/payos-final-acceptance");
        Files.createDirectories(evidence);
        int frontendPort = configuredFrontendPort();
        Path viteLog = evidence.resolve("vite.log");
        Path createBrowserLog = evidence.resolve("browser-create.log");
        Path successBrowserLog = evidence.resolve("browser-success.log");
        Path marker = evidence.resolve("attempt.json");

        ProcessBuilder viteBuilder = new ProcessBuilder("node", "node_modules/vite/bin/vite.js",
                "--host", "127.0.0.1", "--port", Integer.toString(frontendPort), "--strictPort")
                .directory(frontend.toFile()).redirectErrorStream(true).redirectOutput(viteLog.toFile());
        viteBuilder.environment().put("BACKEND_PORT", Integer.toString(backendPort));
        viteBuilder.environment().put("VITE_FEATURE_CART", "true");
        viteBuilder.environment().put("VITE_FEATURE_CHECKOUT", "true");
        viteBuilder.environment().put("VITE_FEATURE_AI_STUDIO", "false");
        viteBuilder.environment().put("VITE_FEATURE_PRINT_QUOTE", "false");
        viteBuilder.environment().put("VITE_DEMO_MODE", "false");
        viteBuilder.environment().put("VITE_DISABLE_BROWSER_OPEN", "true");
        Process vite = viteBuilder.start();
        try {
            awaitFrontend(frontendPort, vite, viteLog);
            runBrowser(frontend, frontendPort, "checkout", createBrowserLog,
                    ownerEmail, otherEmail, marker, null, evidence);
            Map<?, ?> markerData = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(Files.readString(marker), Map.class);
            UUID attemptId = UUID.fromString(String.valueOf(markerData.get("attemptId")));

            Map<String, Object> attempt = jdbc.queryForMap("SELECT order_id, amount_vnd, idempotency_key FROM payment_attempts WHERE attempt_id = ?", attemptId);
            Integer orderId = ((Number) attempt.get("order_id")).intValue();
            String idempotencyKey = String.valueOf(attempt.get("idempotency_key"));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_items WHERE order_id = ?", Integer.class, orderId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT shipping_amount_vnd = 0 AND shipping_confirmation_reference = 'TEST_ONLY_SHIPPING_CONFIRMED' AND payable_amount_vnd = total_amount_vnd AND payment_total_frozen_at IS NOT NULL FROM orders WHERE order_id = ?", Boolean.class, orderId)).isTrue();
            assertThat(jdbc.queryForObject("SELECT provenance FROM payos_payment_links WHERE attempt_id = ?", String.class, attemptId)).isEqualTo("TEST_ONLY");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempts WHERE order_id = ?", Integer.class, orderId)).isEqualTo(1);

            String ownerToken = loginToken(ownerEmail);
            ResponseEntity<Map> replay = postAttempt(ownerToken, orderId, idempotencyKey);
            assertThat(replay.getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(replay.getBody()).containsEntry("attemptId", attemptId.toString());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempts WHERE order_id = ?", Integer.class, orderId)).isEqualTo(1);
            assertThat(payosCreateCalls).hasValue(1);

            String otherToken = loginToken(otherEmail);
            ResponseEntity<String> forbiddenRead = getAttempt(otherToken, attemptId);
            assertThat(forbiddenRead.getStatusCode().value()).isEqualTo(404);
            assertThat(forbiddenRead.getBody()).doesNotContain("TEST_ONLY_FAKE_QR", "123456789", "payos.vn");

            PayosPaymentLink link = links.findById(attemptId).orElseThrow();
            String webhook = signedPaidWebhook(link);
            var settled = webhookProcessor.process(PayosFinalAcceptanceConfiguration.CHANNEL, webhook);
            var duplicate = webhookProcessor.process(PayosFinalAcceptanceConfiguration.CHANNEL, webhook);
            assertThat(settled.disposition()).isEqualTo("MATCHED");
            assertThat(settled.paymentStatus().name()).isEqualTo("SUCCEEDED");
            assertThat(duplicate.paymentStatus().name()).isEqualTo("SUCCEEDED");
            assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE order_id = ?", String.class, orderId)).isEqualTo("SUCCESS");
            assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE order_id = ?", String.class, orderId)).isEqualTo("PROCESSING");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payos_webhook_receipts WHERE order_code = ?", Integer.class, link.getOrderCode())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payos_payment_links WHERE provenance = 'LIVE'", Integer.class)).isZero();

            runBrowser(frontend, frontendPort, "success", successBrowserLog,
                    ownerEmail, otherEmail, marker, attemptId, evidence);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE order_id = ? AND status = 'SUCCESS'", Integer.class, orderId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE order_id = ? AND status = 'PROCESSING'", Integer.class, orderId)).isEqualTo(1);
        } finally {
            stop(vite);
        }
    }

    private Integer seedCustomerWithBackendCart(String email, String name) {
        User user = users.saveAndFlush(User.builder().name(name).email(email).password(passwordEncoder.encode(PASSWORD))
                .role(Role.USER).status(UserStatus.ACTIVE).build());
        Integer printerId = jdbc.queryForObject("INSERT INTO printers(name,status) VALUES (?, 'ACTIVE') RETURNING printer_id", Integer.class, "TEST_ONLY printer " + UUID.randomUUID());
        Integer materialId = jdbc.queryForObject("INSERT INTO materials(name,price_per_gram,stock_quantity,unit,is_available) VALUES (?,1,100,'g','AVAILABLE') RETURNING material_id", Integer.class, "TEST_ONLY material " + UUID.randomUUID());
        Integer modelId = jdbc.queryForObject("INSERT INTO model_3d(source_type,user_id,name,model_status) VALUES ('DESIGNER_MODEL',?,?, 'ACTIVE') RETURNING model_id", Integer.class, user.getId(), "TEST_ONLY model " + UUID.randomUUID());
        Integer estimateId = jdbc.queryForObject("INSERT INTO price_estimations(model_id,material_id,printer_id,total_price,total_price_vnd,policy_version,pricing_policy_snapshot,estimated_at) VALUES (?,?,?,125000,125000,'payment-test-approved','{}',CURRENT_TIMESTAMP) RETURNING estimate_id", Integer.class, modelId, materialId, printerId);
        Integer cartId = jdbc.queryForObject("INSERT INTO cart(user_id,created_at,updated_at) VALUES (?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING cart_id", Integer.class, user.getId());
        jdbc.update("INSERT INTO cart_items(cart_id,model_id,estimate_id,quantity,added_at) VALUES (?,?,?,1,CURRENT_TIMESTAMP)", cartId, modelId, estimateId);
        return user.getId();
    }

    private void installTestOnlyShippingConfirmationFixture() {
        jdbc.execute("""
                CREATE FUNCTION test_only_freeze_vietqr_shipping_total() RETURNS trigger
                LANGUAGE plpgsql AS $$
                BEGIN
                    NEW.shipping_amount_vnd := 0;
                    NEW.shipping_confirmation_reference := 'TEST_ONLY_SHIPPING_CONFIRMED';
                    NEW.payable_amount_vnd := NEW.total_amount_vnd;
                    NEW.payment_total_frozen_at := clock_timestamp();
                    RETURN NEW;
                END $$
                """);
        jdbc.execute("CREATE TRIGGER test_only_freeze_vietqr_shipping BEFORE INSERT ON orders FOR EACH ROW EXECUTE FUNCTION test_only_freeze_vietqr_shipping_total()");
    }

    private String loginToken(String email) {
        var body = Map.of("email", email, "password", PASSWORD);
        ResponseEntity<Map> response = new org.springframework.web.client.RestTemplate().postForEntity(
                "http://127.0.0.1:" + backendPort + "/api/auth/login", body, Map.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return String.valueOf(response.getBody().get("token"));
    }

    private ResponseEntity<Map> postAttempt(String token, Integer orderId, String idempotencyKey) {
        HttpHeaders headers = headers(token);
        headers.set("Idempotency-Key", idempotencyKey);
        return new org.springframework.web.client.RestTemplate().exchange(
                "http://127.0.0.1:" + backendPort + "/api/payments/attempts", HttpMethod.POST,
                new HttpEntity<>(Map.of("orderId", orderId, "method", "BANK_TRANSFER"), headers), Map.class);
    }

    private ResponseEntity<String> getAttempt(String token, UUID attemptId) {
        try {
            return new org.springframework.web.client.RestTemplate().exchange(
                    "http://127.0.0.1:" + backendPort + "/api/payments/attempts/" + attemptId,
                    HttpMethod.GET, new HttpEntity<>(headers(token)), String.class);
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound notFound) {
            return ResponseEntity.status(404).body(notFound.getResponseBodyAsString());
        }
    }

    private HttpHeaders headers(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private String signedPaidWebhook(PayosPaymentLink link) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderCode", link.getOrderCode());
        data.put("amount", link.getAmountVnd());
        data.put("description", link.getDescription());
        data.put("accountNumber", "123456789");
        data.put("reference", "TEST_ONLY_BANK_REFERENCE_" + link.getOrderCode());
        data.put("transactionDateTime", "2026-11-13 00:00:00");
        data.put("currency", "VND");
        data.put("paymentLinkId", link.getPaymentLinkId());
        data.put("code", "00");
        data.put("desc", "TEST_ONLY paid");
        return "{\"code\":\"00\",\"desc\":\"TEST_ONLY\",\"success\":true,\"data\":"
                + new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(data)
                + ",\"signature\":\"" + PayosProtocol.signData(data, PayosFinalAcceptanceConfiguration.CHECKSUM) + "\"}";
    }

    private void runBrowser(Path frontend, int frontendPort, String mode, Path log,
            String ownerEmail, String otherEmail, Path marker, UUID attemptId, Path evidence) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("node", "node_modules/@playwright/test/cli.js", "test",
                "--config=playwright.e2e.config.js", "playwright-e2e/payos-final-acceptance.e2e.js",
                "--grep=" + (mode.equals("checkout") ? "live Backend checkout" : "wrong owner"))
                .directory(frontend.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("PLAYWRIGHT_BASE_URL", "http://127.0.0.1:" + frontendPort);
        builder.environment().put("PAYMENT_E2E_EMAIL", ownerEmail);
        builder.environment().put("PAYMENT_E2E_PASSWORD", PASSWORD);
        builder.environment().put("PAYMENT_E2E_OTHER_EMAIL", otherEmail);
        builder.environment().put("PAYMENT_E2E_OTHER_PASSWORD", PASSWORD);
        builder.environment().put("PAYMENT_ATTEMPT_MARKER", marker.toString());
        builder.environment().put("PAYMENT_E2E_SUCCESS_SCREENSHOT", evidence.resolve("payment-success.png").toString());
        if (attemptId != null) builder.environment().put("PAYMENT_E2E_ATTEMPT_ID", attemptId.toString());
        builder.environment().put("RUNNER_TEMP", evidence.toString());
        Process playwright = builder.start();
        if (!playwright.waitFor(180, TimeUnit.SECONDS)) {
            playwright.destroyForcibly();
            throw new AssertionError("Live FE↔Backend Playwright run timed out: " + readLog(log));
        }
        assertThat(playwright.exitValue()).withFailMessage("Live FE↔Backend browser test failed: %s", readLog(log)).isZero();
        assertThat(readLog(log)).contains("1 passed");
    }

    private static int configuredFrontendPort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress("127.0.0.1", 3000));
            return 3000;
        }
    }

    private static void awaitFrontend(int port, Process process, Path log) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) throw new AssertionError("Vite exited before ready: " + readLog(log));
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
                return;
            } catch (IOException notReady) {
                Thread.sleep(250);
            }
        }
        throw new AssertionError("Vite readiness timed out: " + readLog(log));
    }

    private static void stop(Process process) throws InterruptedException {
        process.destroy();
        if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
    }

    private static String readLog(Path file) throws IOException {
        return Files.exists(file) ? Files.readString(file) : "log file not created";
    }
}
