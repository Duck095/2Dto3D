package com.exe.morphia3d.e2e;

import com.exe.morphia3d.Morphia3dApplication;
import com.exe.morphia3d.config.TestOnlyProductMediaConfiguration;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.Role;
import com.exe.morphia3d.enums.UserStatus;
import com.exe.morphia3d.repository.UserRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Drives the real FE browser against Spring Boot and a disposable PostgreSQL container. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = Morphia3dApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=false",
        "spring.jpa.show-sql=false"
})
@Import(TestOnlyProductMediaConfiguration.class)
class AdminProductCatalogBrowserE2E {
    private static final String ADMIN_PASSWORD = "TEST_ONLY_catalog_admin_2026";
    private static final String USER_PASSWORD = "TEST_ONLY_catalog_user_2026";
    private static final Path TEST_ROOT = createTestRoot();
    private static final Path MEDIA_ROOT = TEST_ROOT.resolve("media");
    private static final Path FIXTURE_ROOT = TEST_ROOT.resolve("fixtures");
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("catalog_admin_browser_e2e")
            .withUsername("catalog_browser_test")
            .withPassword("catalog_browser_test_only");

    static {
        try {
            Files.createDirectories(MEDIA_ROOT);
            Files.createDirectories(FIXTURE_ROOT);
            writeImage("catalog.png", "png");
            writeImage("catalog.jpg", "jpg");
            Files.write(FIXTURE_ROOT.resolve("catalog.webp"), Base64.getDecoder().decode(
                    "UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEAAUAmJaQAA3AA/v89WAAAAA=="));
            Files.write(FIXTURE_ROOT.resolve("catalog.glb"), triangleGlb());
            Files.write(FIXTURE_ROOT.resolve("invalid.png"), new byte[]{1, 2, 3, 4});
            Files.write(FIXTURE_ROOT.resolve("invalid.glb"), new byte[]{'n', 'o', 'p', 'e'});
            Files.write(FIXTURE_ROOT.resolve("e2e-fail-upload.png"), Files.readAllBytes(FIXTURE_ROOT.resolve("catalog.png")));
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @LocalServerPort private int backendPort;
    @Autowired private UserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void configureDisposablePostgres(DynamicPropertyRegistry registry) {
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
        registry.add("test.product-media.root", MEDIA_ROOT::toString);
    }

    @Test
    void adminPublishesAndArchivesProductAndCustomersLoadThePersistedGlbAndImages() throws Exception {
        String nonce = UUID.randomUUID().toString().substring(0, 8);
        String adminEmail = "catalog-admin-" + nonce + "@example.invalid";
        String userEmail = "catalog-user-" + nonce + "@example.invalid";
        saveUser(adminEmail, ADMIN_PASSWORD, Role.ADMIN);
        saveUser(userEmail, USER_PASSWORD, Role.USER);

        Path backendRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path frontend = backendRoot.resolve("../2Dto3D_FE").normalize();
        assertThat(frontend.resolve("node_modules/vite/bin/vite.js")).exists();
        assertThat(frontend.resolve("node_modules/@playwright/test/cli.js")).exists();

        int frontendPort = availablePort(3000);
        Path logs = backendRoot.resolve(".mvn-target/admin-catalog-browser-e2e");
        Files.createDirectories(logs);
        Path viteLog = logs.resolve("vite.log");
        Path browserLog = logs.resolve("playwright.log");
        Path evidence = logs.resolve("evidence");
        Files.createDirectories(evidence);
        ProcessBuilder viteBuilder = new ProcessBuilder("node", "node_modules/vite/bin/vite.js", "--host", "127.0.0.1",
                "--port", Integer.toString(frontendPort), "--strictPort")
                .directory(frontend.toFile()).redirectErrorStream(true).redirectOutput(viteLog.toFile());
        viteBuilder.environment().put("BACKEND_PORT", Integer.toString(backendPort));
        viteBuilder.environment().put("VITE_DEMO_MODE", "false");
        viteBuilder.environment().put("VITE_FEATURE_AI_STUDIO", "false");
        viteBuilder.environment().put("VITE_FEATURE_CART", "false");
        viteBuilder.environment().put("VITE_FEATURE_CHECKOUT", "false");
        viteBuilder.environment().put("VITE_FEATURE_PRINT_QUOTE", "false");
        viteBuilder.environment().put("VITE_DISABLE_BROWSER_OPEN", "true");
        Process vite = viteBuilder.start();
        try {
            awaitFrontend(frontendPort, vite, viteLog);

            ProcessBuilder playwrightBuilder = new ProcessBuilder("node", "node_modules/@playwright/test/cli.js",
                    "test", "--config=playwright.e2e.config.js", "playwright-e2e/admin-product-catalog.e2e.js")
                    .directory(frontend.toFile()).redirectErrorStream(true).redirectOutput(browserLog.toFile());
            playwrightBuilder.environment().put("PLAYWRIGHT_BASE_URL", "http://127.0.0.1:" + frontendPort);
            playwrightBuilder.environment().put("CATALOG_E2E_API_URL", "http://127.0.0.1:" + backendPort);
            playwrightBuilder.environment().put("CATALOG_E2E_ADMIN_EMAIL", adminEmail);
            playwrightBuilder.environment().put("CATALOG_E2E_ADMIN_PASSWORD", ADMIN_PASSWORD);
            playwrightBuilder.environment().put("CATALOG_E2E_USER_EMAIL", userEmail);
            playwrightBuilder.environment().put("CATALOG_E2E_USER_PASSWORD", USER_PASSWORD);
            playwrightBuilder.environment().put("CATALOG_E2E_MEDIA_ROOT", MEDIA_ROOT.toString());
            playwrightBuilder.environment().put("CATALOG_E2E_FIXTURE_ROOT", FIXTURE_ROOT.toString());
            playwrightBuilder.environment().put("CATALOG_E2E_EVIDENCE_DIR", evidence.toString());
            Process playwright = playwrightBuilder.start();
            boolean finished = playwright.waitFor(240, TimeUnit.SECONDS);
            if (!finished) {
                playwright.destroyForcibly();
                throw new AssertionError("Product catalog Playwright E2E timed out. Evidence: " + readLog(browserLog));
            }
            assertThat(playwright.exitValue())
                    .withFailMessage("Product catalog FE↔BE browser E2E failed. Evidence: %s", readLog(browserLog))
                    .isZero();
            assertThat(readLog(browserLog)).contains("1 passed");
            var savedProduct = jdbc.queryForMap("""
                    SELECT model_status, source_type, category, description, file_path_3d, media_asset_id
                    FROM model_3d WHERE name = ?
                    """, "Catalog E2E Product 00");
            assertThat(savedProduct).containsEntry("model_status", "INACTIVE")
                    .containsEntry("source_type", "DESIGNER_MODEL")
                    .containsEntry("category", "Test Catalog Edited")
                    .containsEntry("description", "TEST_ONLY edited product description");
            assertThat((String) savedProduct.get("file_path_3d")).startsWith("product-media/models/");
            Number modelAssetId = (Number) savedProduct.get("media_asset_id");
            assertThat(jdbc.queryForObject("SELECT state FROM media_asset WHERE id = ?", String.class, modelAssetId.longValue()))
                    .isEqualTo("ATTACHED");
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM model_images i
                    JOIN media_asset a ON a.id = i.media_asset_id
                    WHERE i.model_id = (SELECT model_id FROM model_3d WHERE name = ?)
                      AND a.state = 'ATTACHED'
                    """, Integer.class, "Catalog E2E Product 00")).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_3d WHERE name = 'failed upload'", Integer.class))
                    .isZero();
        } finally {
            stop(vite);
        }
    }

    @AfterAll
    static void removeOnlyThisTestMedia() throws IOException {
        if (Files.exists(TEST_ROOT)) {
            try (var paths = Files.walk(TEST_ROOT)) {
                for (Path path : paths.sorted((left, right) -> right.compareTo(left)).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
        assertThat(Files.exists(TEST_ROOT)).isFalse();
    }

    private void saveUser(String email, String password, Role role) {
        users.saveAndFlush(User.builder().name("TEST_ONLY catalog browser " + role).email(email)
                .password(passwordEncoder.encode(password)).role(role).status(UserStatus.ACTIVE).build());
    }

    private static Path createTestRoot() {
        try { return Files.createTempDirectory("2dto3d-catalog-browser-e2e-"); }
        catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }

    private static void writeImage(String filename, String format) throws IOException {
        BufferedImage image = new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x33AADD);
        if (!ImageIO.write(image, format, FIXTURE_ROOT.resolve(filename).toFile())) {
            throw new IOException("No ImageIO writer is registered for " + format);
        }
    }

    private static byte[] triangleGlb() {
        String json = "{\"asset\":{\"version\":\"2.0\",\"generator\":\"TEST_ONLY catalog E2E\"},"
                + "\"scene\":0,\"scenes\":[{\"nodes\":[0]}],\"nodes\":[{\"mesh\":0}],"
                + "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0},\"indices\":1}]}],"
                + "\"buffers\":[{\"byteLength\":44}],\"bufferViews\":["
                + "{\"buffer\":0,\"byteOffset\":0,\"byteLength\":36,\"target\":34962},"
                + "{\"buffer\":0,\"byteOffset\":36,\"byteLength\":6,\"target\":34963}],"
                + "\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\","
                + "\"min\":[-0.5,0,-0.5],\"max\":[0.5,1,0.5]},{\"bufferView\":1,"
                + "\"componentType\":5123,\"count\":3,\"type\":\"SCALAR\"}]}";
        byte[] jsonRaw = json.getBytes(StandardCharsets.UTF_8);
        int jsonLength = (jsonRaw.length + 3) & ~3;
        ByteBuffer binary = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                .putFloat(-0.5f).putFloat(0).putFloat(-0.5f)
                .putFloat(0.5f).putFloat(0).putFloat(-0.5f)
                .putFloat(0).putFloat(1).putFloat(0.5f)
                .putShort((short) 0).putShort((short) 1).putShort((short) 2).putShort((short) 0);
        int totalLength = 12 + 8 + jsonLength + 8 + binary.capacity();
        ByteBuffer glb = ByteBuffer.allocate(totalLength).order(ByteOrder.LITTLE_ENDIAN);
        glb.putInt(0x46546C67).putInt(2).putInt(totalLength);
        glb.putInt(jsonLength).putInt(0x4E4F534A).put(jsonRaw);
        while (glb.position() < 20 + jsonLength) glb.put((byte) 0x20);
        glb.putInt(binary.capacity()).putInt(0x004E4942).put(binary.array());
        return glb.array();
    }

    private static int availablePort(int preferred) throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress("127.0.0.1", preferred));
            return socket.getLocalPort();
        }
    }

    private static void awaitFrontend(int port, Process process, Path viteLog) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) throw new AssertionError("Vite exited early. Evidence: " + readLog(viteLog));
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
                return;
            } catch (IOException stillStarting) {
                Thread.sleep(250);
            }
        }
        throw new AssertionError("Vite did not start. Evidence: " + readLog(viteLog));
    }

    private static void stop(Process process) throws InterruptedException {
        process.destroy();
        if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
    }

    private static String readLog(Path file) throws IOException {
        return Files.exists(file) ? Files.readString(file) : "log file not created";
    }
}
