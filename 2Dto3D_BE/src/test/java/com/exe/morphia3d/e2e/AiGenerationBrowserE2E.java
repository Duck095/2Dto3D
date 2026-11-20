package com.exe.morphia3d.e2e;

import com.exe.morphia3d.Morphia3dApplication;
import com.exe.morphia3d.config.TestOnlyAiGenerationConfiguration;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.Role;
import com.exe.morphia3d.enums.UserStatus;
import com.exe.morphia3d.repository.UserRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the FE browser flow against the real embedded Backend and disposable PostgreSQL. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = Morphia3dApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=false",
        "app.features.ai.enabled=true",
        "app.ai-generation.enabled=true",
        "app.ai-generation.worker-enabled=true",
        "app.ai-generation.worker-interval-millis=100"
})
@Import(TestOnlyAiGenerationConfiguration.class)
class AiGenerationBrowserE2E {
    private static final String DATABASE = "ai_browser_isolated";
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName(DATABASE)
            .withUsername("ai_browser_test")
            .withPassword("ai_browser_test_only");

    @DynamicPropertySource
    static void configureIsolatedPostgres(DynamicPropertyRegistry registry) {
        if (!POSTGRES.isRunning()) POSTGRES.start();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .schemas("public")
                .defaultSchema("public")
                .cleanDisabled(true)
                .load()
                .migrate();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @LocalServerPort private int backendPort;
    @Autowired private UserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    void authenticatedDesktopAndMobileBrowsersUseTheLiveBackendAndTestOnlyProvider() throws Exception {
        Path backendRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path frontend = backendRoot.resolve("../2Dto3D_FE").normalize();
        assertThat(frontend.resolve("node_modules/vite/bin/vite.js")).exists();
        assertThat(frontend.resolve("node_modules/@playwright/test/cli.js")).exists();

        String email = "ai-browser-" + UUID.randomUUID() + "@example.invalid";
        String password = "TEST_ONLY_browser_password_2026";
        users.saveAndFlush(User.builder()
                .name("TEST_ONLY browser customer")
                .email(email)
                .password(passwordEncoder.encode(password))
                .role(Role.USER)
                .status(UserStatus.ACTIVE)
                .build());

        int frontendPort = configuredFrontendPort();
        String frontendUrl = "http://127.0.0.1:" + frontendPort;
        Path logs = backendRoot.resolve(".mvn-target/ai-browser-e2e");
        Files.createDirectories(logs);
        Path viteLog = logs.resolve("vite.log");
        Path browserLog = logs.resolve("playwright.log");

        ProcessBuilder viteBuilder = new ProcessBuilder("node", "node_modules/vite/bin/vite.js",
                "--host", "127.0.0.1", "--port", Integer.toString(frontendPort), "--strictPort")
                .directory(frontend.toFile())
                .redirectErrorStream(true)
                .redirectOutput(viteLog.toFile());
        viteBuilder.environment().put("BACKEND_PORT", Integer.toString(backendPort));
        viteBuilder.environment().put("VITE_FEATURE_AI_STUDIO", "true");
        viteBuilder.environment().put("VITE_FEATURE_CART", "false");
        viteBuilder.environment().put("VITE_FEATURE_CHECKOUT", "false");
        viteBuilder.environment().put("VITE_FEATURE_PRINT_QUOTE", "false");
        viteBuilder.environment().put("VITE_DEMO_MODE", "false");
        viteBuilder.environment().put("VITE_DISABLE_BROWSER_OPEN", "true");
        Process vite = viteBuilder.start();
        try {
            awaitFrontend(frontendPort, vite, viteLog);
            ProcessBuilder playwrightBuilder = new ProcessBuilder("node", "node_modules/@playwright/test/cli.js", "test",
                    "--config=playwright.e2e.config.js", "playwright-e2e/ai-studio-generation-live.e2e.js")
                    .directory(frontend.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(browserLog.toFile());
            playwrightBuilder.environment().put("PLAYWRIGHT_BASE_URL", frontendUrl);
            playwrightBuilder.environment().put("AI_E2E_EMAIL", email);
            playwrightBuilder.environment().put("AI_E2E_PASSWORD", password);
            Process playwright = playwrightBuilder.start();
            boolean finished = playwright.waitFor(180, TimeUnit.SECONDS);
            if (!finished) {
                playwright.destroyForcibly();
                throw new AssertionError("Playwright live FE↔BE run timed out. Evidence: " + readLog(browserLog));
            }
            assertThat(playwright.exitValue())
                    .withFailMessage("Live FE↔BE browser run failed. Evidence: %s", readLog(browserLog))
                    .isZero();
            assertThat(readLog(browserLog)).contains("2 passed");
        } finally {
            stop(vite);
        }
    }

    private static int configuredFrontendPort() throws IOException {
        int port = 3000;
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress("127.0.0.1", port));
            return port;
        }
    }

    private static void awaitFrontend(int port, Process process, Path viteLog) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new AssertionError("Vite exited before becoming ready. Evidence: " + readLog(viteLog));
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
                return;
            } catch (IOException ignored) {
                // Vite is still starting; keep waiting until the bounded deadline.
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Vite did not become ready. Evidence: " + readLog(viteLog));
    }

    private static void stop(Process process) throws InterruptedException {
        process.destroy();
        if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
    }

    private static String readLog(Path file) throws IOException {
        return Files.exists(file) ? Files.readString(file) : "log file not created";
    }
}
