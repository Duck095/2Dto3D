package com.exe.morphia3d.controller;

import com.exe.morphia3d.Morphia3dApplication;
import com.exe.morphia3d.controller.admin3d.AdminProductMeshController;
import com.exe.morphia3d.feature.FeatureGateProperties;
import com.exe.morphia3d.enums.MediaKind;
import com.exe.morphia3d.enums.MediaProvider;
import com.exe.morphia3d.dto.request.DesignerModelRequest;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.Role;
import com.exe.morphia3d.security.UserPrincipal;
import com.exe.morphia3d.service.DesignerModelUploadService;
import com.exe.morphia3d.service.MediaStorageService;
import com.exe.morphia3d.service.MediaUploadIntent;
import com.exe.morphia3d.service.MediaUploadResult;
import com.exe.morphia3d.service.ValidatedMedia;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.imageio.ImageIO;
import javax.sql.DataSource;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

/** Isolated full FE/Vite -> Spring/JWT -> PostgreSQL acceptance with fake media storage. */
@SpringBootTest(classes = Morphia3dApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.jpa.hibernate.ddl-auto=none",
                "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
                "spring.jpa.open-in-view=false",
                "spring.datasource.hikari.connection-timeout=1000",
                "spring.flyway.enabled=false",
                "media.lifecycle.reconciliation-enabled=false",
                "app.features.admin-product-snapshot-upload.enabled=true",
                "cloudinary.cloud-name=test-cloud",
                "media.upload.snapshot-temp-directory=${admin3d.e2e.temp-directory:${user.dir}/.mvn-target/admin3d-e2e-temp}/snapshot-spool"
        })
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class Admin3dRealE2ePostgresAcceptanceTest {
    private static final String IMAGE = "postgres@sha256:721873c34ceb9f8d8fc265984940dc982404c105f19ad51be9fdc5970a6080ea";
    private static final String MIGRATOR_PASSWORD = "admin3d_e2e_migrator_test_only";
    private static final String RUNTIME_PASSWORD = "admin3d_e2e_runtime_test_only";
    private static final String ADMIN_EMAIL = "admin-e2e@2dto3d.invalid";
    private static final String ADMIN_PASSWORD = "E2e-Admin-Only-826!";
    private static final String SECOND_ADMIN_EMAIL = "admin-second-e2e@2dto3d.invalid";
    private static final String SECOND_ADMIN_PASSWORD = "E2e-Admin-Second-826!";
    private static final String USER_EMAIL = "user-e2e@2dto3d.invalid";
    private static final String USER_PASSWORD = "E2e-User-Only-826!";
    private static final Path BACKEND_ROOT = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    private static final Path ROOT = BACKEND_ROOT.getParent();
    private static final Path FE_ROOT = ROOT.resolve("2Dto3D_FE");
    private static final Path EVIDENCE = Path.of(System.getProperty("admin3d.e2e.evidence-directory",
            ROOT.resolve(".mvn-target/admin3d-e2e/evidence").toString()));
    private static final Path OUTAGE_EVIDENCE = Path.of(System.getProperty("admin3d.e2e.outage-evidence-directory",
            ROOT.resolve(".mvn-target/admin3d-e2e/outage-evidence").toString()));
    private static final Path TEMP = Path.of(System.getProperty("admin3d.e2e.temp-directory",
            BACKEND_ROOT.resolve(".mvn-target/admin3d-e2e-temp").toString())).toAbsolutePath().normalize();
    private static final Path SPOOL = TEMP.resolve("snapshot-spool");
    private static final Path MESH_TEMP = Path.of(System.getProperty("java.io.tmpdir", "."))
            .toAbsolutePath().normalize();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate bootstrap;
    private static int adminId;
    private static int secondAdminId;
    private static int userId;
    private static int materialId;
    private static int whiteColorId;
    private static int blueColorId;

    static {
        try {
            prepareDisposablePostgres();
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @Autowired private Environment environment;
    @Autowired private JdbcTemplate runtimeJdbc;
    @Autowired private FeatureGateProperties featureProperties;
    @Autowired private DesignerModelUploadService uploadService;
    @LocalServerPort private int backendPort;
    @MockitoBean private MediaStorageService storage;
    @MockitoSpyBean private AdminProductMeshController meshController;
    private final List<StoredUpload> storedUploads = new CopyOnWriteArrayList<>();
    private Process vite;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        if (postgres == null || !postgres.isRunning()) {
            try {
                prepareDisposablePostgres();
            } catch (Exception failure) {
                throw new IllegalStateException("Failed to reprovision disposable Admin 3D PostgreSQL", failure);
            }
        }
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "app_runtime");
        registry.add("spring.datasource.password", () -> RUNTIME_PASSWORD);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @AfterEach
    void stopOnlyThisTestViteAndVerifySpools() throws Exception {
        if (vite != null && vite.isAlive()) {
            vite.destroy();
            if (!vite.waitFor(5, TimeUnit.SECONDS)) vite.destroyForcibly();
        }
        verifySnapshotTempPath();
        try (var files = Files.list(SPOOL)) {
            assertThat(files.toList()).as("trusted upload spool is empty").isEmpty();
        }
    }

    private void verifySnapshotTempPath() throws IOException {
        Path configuredSpool = Path.of(environment.getRequiredProperty("media.upload.snapshot-temp-directory"))
                .toAbsolutePath().normalize();
        Path assertedSpool = SPOOL.toAbsolutePath().normalize();
        assertThat(configuredSpool).as("configured media upload temp matches test spool assertion")
                .isEqualTo(assertedSpool);
        Files.createDirectories(EVIDENCE);
        Files.writeString(EVIDENCE.resolve("snapshot-temp-path.txt"),
                "configured=" + configuredSpool + System.lineSeparator()
                        + "asserted=" + assertedSpool + System.lineSeparator());
    }
    @AfterAll
    static void stopOnlyDisposablePostgres() {
        if (postgres != null && postgres.isRunning()) postgres.stop();
    }

    @Test
    void realBrowserUploadSnapshotConfigureReloadAndPostgresBoundaries() throws Exception {
        Files.createDirectories(EVIDENCE);
        Files.createDirectories(TEMP);
        Files.createDirectories(SPOOL);
        verifySnapshotTempPath();
        try (var files = Files.list(SPOOL)) { assertThat(files.toList()).isEmpty(); }
        storedUploads.clear();
        reset(storage);
        doAnswer(invocation -> {
            MediaUploadIntent intent = invocation.getArgument(0);
            ValidatedMedia media = invocation.getArgument(1);
            byte[] bytes;
            try (var stream = media.file().getInputStream()) { bytes = stream.readAllBytes(); }
            String format = media.format();
            String suffix = media.mediaKind() == MediaKind.IMAGE ? "." + format : "";
            storedUploads.add(new StoredUpload(intent.mediaKind(), bytes, sha256(bytes)));
            return new MediaUploadResult(MediaProvider.CLOUDINARY, "test-cloud",
                    "fake-e2e-asset-" + intent.mediaAssetId() + "-" + UUID.randomUUID(), intent.publicId(),
                    intent.mediaKind(), intent.resourceType(), intent.deliveryType(), format, bytes.length, 1L,
                    "https://res.cloudinary.com/test-cloud/" + intent.resourceType() + "/upload/v1/"
                            + intent.publicId() + suffix);
        }).when(storage).upload(any(), any());

        Set<Path> meshSpoolsBefore = meshSpools();
        String backendLoginToken = loginToken(ADMIN_EMAIL, ADMIN_PASSWORD);
        Files.writeString(EVIDENCE.resolve("backend-auth-precheck.json"),
                JSON.writeValueAsString(java.util.Map.of("status", 200, "tokenReturnedInMemory", !backendLoginToken.isBlank())));
        // SecurityConfig explicitly allows http://127.0.0.1:3000 in the test profile.
        // Keep the browser origin on that configured test origin; backend remains isolated.
        int vitePort = 3000;
        vitePortForSummary = vitePort;
        startVite(vitePort);
        runPlaywright(vitePort);
        JsonNode result = JSON.readTree(Files.readString(EVIDENCE.resolve("browser-result.json")));
        assertThat(result.path("productId").asInt()).isPositive();
        assertThat(result.path("snapshotRevision").asInt()).isEqualTo(1);
        assertThat(result.path("meshOccurrences").asInt()).isEqualTo(2);

        int productId = result.path("productId").asInt();
        long mediaAssetId = runtimeJdbc.queryForObject(
                "SELECT media_asset_id FROM model_3d WHERE model_id=?", Long.class, productId);
        UUID snapshotId = runtimeJdbc.queryForObject("""
                SELECT structure_snapshot_id FROM product_glb_structure_snapshots
                WHERE product_id=? AND media_asset_id=?
                """, (rs, row) -> rs.getObject(1, UUID.class), productId, mediaAssetId);
        assertThat(snapshotId.toString()).isEqualTo(result.path("structureSnapshotId").asText());
        assertThat(runtimeJdbc.queryForObject("""
                SELECT count(*) FROM model_3d WHERE model_id=? AND user_id=? AND media_asset_id=?
                """, Integer.class, productId, adminId, mediaAssetId)).isEqualTo(1);
        assertThat(runtimeJdbc.queryForObject("SELECT count(*) FROM model_images WHERE model_id=?",
                Integer.class, productId)).isEqualTo(1);
        assertThat(runtimeJdbc.queryForObject("""
                SELECT count(*) FROM media_asset asset
                WHERE asset.operation_id=(SELECT operation_id FROM media_asset WHERE id=?) AND asset.state='ATTACHED'
                """, Integer.class, mediaAssetId)).isEqualTo(2);

        JsonNode structure = runtimeJdbc.queryForObject("""
                SELECT structure_json::text FROM product_glb_structure_snapshots
                WHERE product_id=? AND media_asset_id=?
                """, (rs, row) -> readJson(rs.getString(1)), productId, mediaAssetId);
        String parserVersion = runtimeJdbc.queryForObject("""
                SELECT parser_version FROM product_glb_structure_snapshots
                WHERE product_id=? AND media_asset_id=?
                """, String.class, productId, mediaAssetId);
        assertThat(parserVersion).isEqualTo("glb-structure-1");
        assertThat(structure.path("primitiveOccurrences")).hasSize(2);
        assertThat(structure.path("primitiveOccurrences").get(0).path("nodeIndex").asInt()).isZero();
        assertThat(structure.path("primitiveOccurrences").get(0).path("meshIndex").asInt()).isZero();
        assertThat(structure.path("primitiveOccurrences").get(0).path("primitiveIndex").asInt()).isZero();
        assertThat(structure.path("primitiveOccurrences").get(1).path("primitiveIndex").asInt()).isEqualTo(1);

        JsonNode revision = runtimeJdbc.queryForObject("""
                SELECT configuration_json::text FROM product_mesh_configuration_revisions
                WHERE product_id=? AND config_revision=1
                """, (rs, row) -> readJson(rs.getString(1)), productId);
        Integer persistedMaterialId = runtimeJdbc.queryForObject("""
                SELECT material_id FROM product_mesh_configuration_revisions
                WHERE product_id=? AND config_revision=1
                """, Integer.class, productId);
        assertThat(persistedMaterialId).isEqualTo(materialId);
        assertThat(revision.path("meshes")).hasSize(2);
        assertThat(revision.path("meshes").get(0).path("customizable").asBoolean()).isTrue();
        assertThat(revision.path("meshes").get(0).path("allowedColorIds").toString())
                .contains(whiteColorId + "", blueColorId + "");
        assertThat(revision.path("meshes").get(1).path("customizable").asBoolean()).isFalse();
        assertThat(revision.path("meshes").get(1).path("defaultColorId").asInt()).isEqualTo(whiteColorId);
        assertThat(runtimeJdbc.queryForObject("""
                SELECT count(*) FROM product_mesh_configuration_revisions WHERE product_id=?
                """, Integer.class, productId)).isEqualTo(2);

        assertThat(storedUploads).hasSize(2);
        assertThat(storedUploads.stream().map(StoredUpload::kind).toList())
                .containsExactly(MediaKind.MODEL_3D, MediaKind.IMAGE);
        String glbSha = result.path("fixtureGlbSha256").asText();
        StoredUpload storedGlb = storedUploads.get(0);
        assertThat(storedGlb.sha256()).isEqualTo(glbSha);
        assertThat(storedGlb.bytes()).hasSize(result.path("fixtureGlbBytes").asInt());
        assertThat(storedUploads.get(1).sha256()).isEqualTo(result.path("fixtureImageSha256").asText());
        verify(storage, times(2)).upload(any(), any());
        verify(storage, never()).delete(any());

        assertThat(meshSpools()).as("chunked >32 MiB body leaves no request spool")
                .containsExactlyInAnyOrderElementsOf(meshSpoolsBefore);
        int configRows = runtimeJdbc.queryForObject(
                "SELECT count(*) FROM product_mesh_configuration_revisions WHERE product_id=?",
                Integer.class, productId);
        assertThat(configRows).isEqualTo(2);
        int productRows = runtimeJdbc.queryForObject(
                "SELECT count(*) FROM model_3d WHERE model_id=?", Integer.class, productId);
        int imageRows = runtimeJdbc.queryForObject(
                "SELECT count(*) FROM model_images WHERE model_id=?", Integer.class, productId);
        int snapshotRows = runtimeJdbc.queryForObject("""
                SELECT count(*) FROM product_glb_structure_snapshots
                WHERE product_id=? AND media_asset_id=?
                """, Integer.class, productId, mediaAssetId);
        Files.writeString(EVIDENCE.resolve("postgres-acceptance.json"), JSON.writeValueAsString(
                java.util.Map.ofEntries(
                        java.util.Map.entry("postgresVersion", "16.15"),
                        java.util.Map.entry("database", "task-owned disposable Testcontainers PostgreSQL"),
                        java.util.Map.entry("productRowsForAcceptedProduct", productRows),
                        java.util.Map.entry("imageRowsForAcceptedProduct", imageRows),
                        java.util.Map.entry("attachedMediaRowsForUploadOperation", 2),
                        java.util.Map.entry("snapshotRowsForProductAndMedia", snapshotRows),
                        java.util.Map.entry("configurationRevisionRowsForProduct", configRows),
                        java.util.Map.entry("parserVersionColumn", parserVersion),
                        java.util.Map.entry("materialIdColumn", persistedMaterialId),
                        java.util.Map.entry("glbSha256Input", glbSha),
                        java.util.Map.entry("glbSha256FakeStorage", storedGlb.sha256()),
                        java.util.Map.entry("requestSpoolCleanupVerified", true))));

        legacyUploadWithFeatureFlagOff();
        verify(storage, times(4)).upload(any(), any());

        String token = loginToken(ADMIN_EMAIL, ADMIN_PASSWORD);
        String outageUri = "http://127.0.0.1:" + backendPort
                + "/api/admin/products/" + productId + "/mesh-configurations";
        postgres.stop();
        HttpResponse<String> unavailable = HTTP.send(HttpRequest.newBuilder(URI.create(outageUri))
                .timeout(Duration.ofSeconds(12)).header("Authorization", "Bearer " + token).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        Files.writeString(EVIDENCE.resolve("postgres-outage-http.json"),
                JSON.writeValueAsString(java.util.Map.of("status", unavailable.statusCode(),
                        "contentType", unavailable.headers().firstValue("content-type").orElse(""),
                        "body", unavailable.body())));
        assertThat(unavailable.statusCode()).isEqualTo(503);
        assertThat(unavailable.body()).contains("PERSISTENCE_UNAVAILABLE")
                .doesNotContain("jdbc:", RUNTIME_PASSWORD, "SQLState", "SQLException", "password");

        writeRuntimeSummary(result, unavailable.statusCode());
    }

    @Test
    void adminMeshDatabaseOutageReturnsSanitized503BeforeController() throws Exception {
        Files.createDirectories(OUTAGE_EVIDENCE);
        Files.createDirectories(SPOOL);
        verifySnapshotTempPath();
        try (var files = Files.list(SPOOL)) { assertThat(files.toList()).isEmpty(); }
        reset(storage);
        doAnswer(invocation -> {
            MediaUploadIntent intent = invocation.getArgument(0);
            ValidatedMedia media = invocation.getArgument(1);
            byte[] bytes;
            try (var input = media.file().getInputStream()) { bytes = input.readAllBytes(); }
            String format = media.format();
            String suffix = media.mediaKind() == MediaKind.IMAGE ? "." + format : "";
            return new MediaUploadResult(MediaProvider.CLOUDINARY, "test-cloud",
                    "fake-503-asset-" + intent.mediaAssetId(), intent.publicId(), intent.mediaKind(),
                    intent.resourceType(), intent.deliveryType(), format, bytes.length, 1L,
                    "https://res.cloudinary.com/test-cloud/" + intent.resourceType() + "/upload/v1/"
                            + intent.publicId() + suffix);
        }).when(storage).upload(any(), any());

        Path acceptedFixture = Path.of(System.getProperty("admin3d.e2e.fixture-directory", EVIDENCE.toString()));
        ensureAcceptanceFixtures(acceptedFixture);
        byte[] glb = Files.readAllBytes(acceptedFixture.resolve("fixture.glb"));
        byte[] image = Files.readAllBytes(acceptedFixture.resolve("fixture.png"));
        User admin = User.builder().id(adminId).name("Admin E2E").email(ADMIN_EMAIL)
                .password("test-only-unused").role(Role.ADMIN).build();
        UserPrincipal principal = new UserPrincipal(admin);
        DesignerModelRequest metadata = new DesignerModelRequest();
        metadata.setName("Admin Mesh outage retest " + UUID.randomUUID());
        metadata.setCategory("outage-retest");
        metadata.setDescription("Disposable PostgreSQL outage retest fixture");
        var created = uploadService.createWithSnapshot(principal, metadata,
                List.of(new MockMultipartFile("images", "front.png", "image/png", image)),
                new MockMultipartFile("model", "source.glb", "model/gltf-binary", glb));
        int productId = created.getId();
        verify(storage, times(2)).upload(any(), any());
        verify(storage, never()).delete(any());

        String token = loginToken(ADMIN_EMAIL, ADMIN_PASSWORD);
        String base = "http://127.0.0.1:" + backendPort;
        String version = runtimeJdbc.queryForObject("SELECT version()", String.class);
        assertThat(productId).isPositive();

        String structurePath = "/api/admin/products/" + productId + "/mesh-structure";
        String configurationPath = "/api/admin/products/" + productId + "/mesh-configurations";
        HttpResponse<String> onlineStructure = HTTP.send(HttpRequest.newBuilder(URI.create(base + structurePath))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + token)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> onlineConfiguration = HTTP.send(HttpRequest.newBuilder(URI.create(base + configurationPath))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + token)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(onlineStructure.statusCode()).isEqualTo(200);
        assertThat(onlineConfiguration.statusCode()).isEqualTo(200);
        String jdbcUrlBeforeOutage = postgres.getJdbcUrl();
        Files.writeString(OUTAGE_EVIDENCE.resolve("503-pre-outage.json"), JSON.writeValueAsString(
                java.util.Map.of("postgresVersion", version, "containerImage", postgres.getDockerImageName(),
                        "containerId", postgres.getContainerId(), "adminJwtLoginStatus", 200,
                        "structureStatus", onlineStructure.statusCode(),
                        "configurationStatus", onlineConfiguration.statusCode(), "productId", productId)));

        clearInvocations(meshController);
        postgres.stop();
        HttpResponse<String> structure = HTTP.send(HttpRequest.newBuilder(URI.create(base + structurePath))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + token)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> configuration = HTTP.send(HttpRequest.newBuilder(URI.create(base + configurationPath))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + token)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> put = HTTP.send(HttpRequest.newBuilder(URI.create(base + configurationPath))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{" )).build(), HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> invalidJwt = HTTP.send(HttpRequest.newBuilder(URI.create(base + structurePath))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer invalid-test-token")
                .GET().build(), HttpResponse.BodyHandlers.ofString());

        var responses = List.of(structure, configuration, put);
        var responseEvidence = responses.stream().map(response -> java.util.Map.of(
                "method", response.request().method(), "status", response.statusCode(),
                "contentType", response.headers().firstValue("content-type").orElse(""),
                "cacheControl", response.headers().firstValue("cache-control").orElse(""),
                "body", response.body())).toList();
        Files.writeString(OUTAGE_EVIDENCE.resolve("503-outage-responses.json"), JSON.writeValueAsString(
                java.util.Map.of("invalidJwt", java.util.Map.of("status", invalidJwt.statusCode(),
                                "contentType", invalidJwt.headers().firstValue("content-type").orElse(""),
                                "body", invalidJwt.body()), "validAdminRequests", responseEvidence)));

        String privateData = jdbcUrlBeforeOutage + RUNTIME_PASSWORD + MIGRATOR_PASSWORD + token;
        for (HttpResponse<String> response : responses) {
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.headers().firstValue("content-type").orElse(""))
                    .startsWith("application/json");
            assertThat(response.headers().firstValue("cache-control").orElse(""))
                    .isEqualTo("no-store");
            assertThat(response.body()).isEqualTo("{\"code\":\"PERSISTENCE_UNAVAILABLE\","
                    + "\"message\":\"The persistence service is temporarily unavailable.\"}");
            assertThat(response.body()).doesNotContain(privateData, "jdbc:", "SQLException", "SQLState", "password");
        }
        assertThat(invalidJwt.statusCode()).isEqualTo(401);
        assertThat(invalidJwt.body()).doesNotContain("PERSISTENCE_UNAVAILABLE", jdbcUrlBeforeOutage,
                RUNTIME_PASSWORD, MIGRATOR_PASSWORD, token, "jdbc:", "SQLState", "SQLException");
        verify(meshController, never()).getStructure(productId);
        verify(meshController, never()).getConfiguration(productId);
        verify(meshController, never()).putConfiguration(eq(productId), any(), any());
    }

    private void startVite(int port) throws Exception {
        Path viteLog = EVIDENCE.resolve("vite.log");
        ProcessBuilder builder = new ProcessBuilder("node", "node_modules/vite/bin/vite.js",
                "--host", "127.0.0.1", "--port", Integer.toString(port), "--strictPort")
                .directory(FE_ROOT.toFile()).redirectErrorStream(true).redirectOutput(viteLog.toFile());
        builder.environment().put("BACKEND_PORT", Integer.toString(backendPort));
        builder.environment().put("VITE_API_BASE_URL", "/api");
        builder.environment().put("VITE_DEMO_MODE", "false");
        builder.environment().put("VITE_DISABLE_BROWSER_OPEN", "true");
        vite = builder.start();
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline && vite.isAlive()) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
                return;
            } catch (IOException ignored) { Thread.sleep(250); }
        }
        throw new IllegalStateException("Isolated Vite instance did not become ready; inspect task evidence vite.log");
    }

    private void runPlaywright(int vitePort) throws Exception {
        Path evidencePath = EVIDENCE.toAbsolutePath();
        Path runnerTemp = EVIDENCE.resolve("runner-temp");
        Files.createDirectories(runnerTemp);
        ProcessBuilder builder = new ProcessBuilder("node", "node_modules/@playwright/test/cli.js", "test",
                "--config=playwright.e2e.config.js", "playwright-e2e/admin3d-real-e2e.e2e.js")
                .directory(FE_ROOT.toFile()).redirectErrorStream(true)
                .redirectOutput(EVIDENCE.resolve("playwright.log").toFile());
        builder.environment().put("PLAYWRIGHT_BASE_URL", "http://127.0.0.1:" + vitePort);
        builder.environment().put("RUNNER_TEMP", runnerTemp.toAbsolutePath().toString());
        builder.environment().put("ADMIN3D_E2E_API_URL", "http://127.0.0.1:" + backendPort);
        builder.environment().put("ADMIN3D_E2E_EVIDENCE_DIR", evidencePath.toString());
        builder.environment().put("ADMIN3D_E2E_ADMIN_EMAIL", ADMIN_EMAIL);
        builder.environment().put("ADMIN3D_E2E_ADMIN_PASSWORD", ADMIN_PASSWORD);
        builder.environment().put("ADMIN3D_E2E_SECOND_ADMIN_EMAIL", SECOND_ADMIN_EMAIL);
        builder.environment().put("ADMIN3D_E2E_SECOND_ADMIN_PASSWORD", SECOND_ADMIN_PASSWORD);
        builder.environment().put("ADMIN3D_E2E_USER_EMAIL", USER_EMAIL);
        builder.environment().put("ADMIN3D_E2E_USER_PASSWORD", USER_PASSWORD);
        Process playwright = builder.start();
        boolean finished = playwright.waitFor(240, TimeUnit.SECONDS);
        if (!finished) {
            playwright.destroyForcibly();
            throw new IllegalStateException("Playwright E2E exceeded four-minute test limit");
        }
        int exit = playwright.exitValue();
        Files.writeString(EVIDENCE.resolve("playwright.exit"), Integer.toString(exit), StandardCharsets.US_ASCII);
        assertThat(exit).as("focused real browser acceptance; see task evidence attempt6/playwright.log").isZero();
    }

    private void legacyUploadWithFeatureFlagOff() throws Exception {
        FeatureGateProperties.Toggle toggle = featureProperties.getAdminProductSnapshotUpload();
        boolean previous = toggle.isEnabled();
        toggle.setEnabled(false);
        try {
            String token = loginToken(ADMIN_EMAIL, ADMIN_PASSWORD);
            byte[] png = Files.readAllBytes(EVIDENCE.resolve("fixture.png"));
            byte[] headerOnlyGlb = new byte[]{'g', 'l', 'T', 'F', 2, 0, 0, 0, 12, 0, 0, 0};
            int productsBefore = runtimeJdbc.queryForObject("SELECT count(*) FROM model_3d", Integer.class);
            int snapshotsBefore = runtimeJdbc.queryForObject(
                    "SELECT count(*) FROM product_glb_structure_snapshots", Integer.class);
            String boundary = "----admin3d-e2e-" + UUID.randomUUID();
            byte[] body = legacyMultipart(boundary, png, headerOnlyGlb);
            HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(
                            "http://127.0.0.1:" + backendPort + "/api/admin/products"))
                    .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + token)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString());
            Files.writeString(EVIDENCE.resolve("legacy-flag-off-http.json"),
                    JSON.writeValueAsString(java.util.Map.of("status", response.statusCode(),
                            "contentType", response.headers().firstValue("content-type").orElse(""),
                            "body", JSON.readTree(response.body()))));
            assertThat(response.statusCode()).isEqualTo(201);
            assertThat(runtimeJdbc.queryForObject("SELECT count(*) FROM model_3d", Integer.class))
                    .isEqualTo(productsBefore + 1);
            assertThat(runtimeJdbc.queryForObject("SELECT count(*) FROM product_glb_structure_snapshots", Integer.class))
                    .isEqualTo(snapshotsBefore);
        } finally {
            toggle.setEnabled(previous);
        }
    }

    private String loginToken(String email, String password) throws Exception {
        String json = JSON.writeValueAsString(java.util.Map.of("email", email, "password", password));
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + backendPort + "/api/auth/login"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Disposable Backend login failed; status=" + response.statusCode()
                    + "; sanitized response=" + response.body().replace(ADMIN_EMAIL, "<test-admin>")
                    .replace(ADMIN_PASSWORD, "<redacted>"));
        }
        return JSON.readTree(response.body()).path("token").asText();
    }

    private static byte[] legacyMultipart(String boundary, byte[] image, byte[] glb) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writePart(body, boundary, "data", "data.json", "application/json",
                "{\"name\":\"E2E legacy flag off\",\"category\":\"test-only\",\"description\":\"legacy verification\"}"
                        .getBytes(StandardCharsets.UTF_8));
        writePart(body, boundary, "images", "legacy.png", "image/png", image);
        writePart(body, boundary, "model", "legacy-header-only.glb", "model/gltf-binary", glb);
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        return body.toByteArray();
    }

    private static void writePart(ByteArrayOutputStream output, String boundary, String name, String filename,
                                  String contentType, byte[] bytes) throws IOException {
        output.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name
                + "\"; filename=\"" + filename + "\"\r\nContent-Type: " + contentType + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        output.write(bytes);
        output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
    }

    private void writeRuntimeSummary(JsonNode result, int outageStatus) throws Exception {
        String summary = """
                FE Vite origin: %s
                Spring random port: %d
                PostgreSQL disposable: PostgreSQL 16; database admin3d_e2e_acceptance; runtime role app_runtime
                Browser engine: Playwright 1.64.1 / Microsoft Edge Chromium
                Main product ID: %d
                Snapshot ID: %s
                Material ID: %d; color IDs white=%d blue=%d
                Main GLB SHA-256: %s
                Media fake uploads: %d; provider deletes: 0
                PostgreSQL outage HTTP status: %d
                """.formatted(result.path("viteOrigin").asText(), vitePortForSummary,
                result.path("productId").asInt(), result.path("structureSnapshotId").asText(),
                materialId, whiteColorId, blueColorId, result.path("fixtureGlbSha256").asText(),
                storedUploads.size(), outageStatus);
        Files.writeString(EVIDENCE.resolve("runtime-summary.txt"), summary);
    }

    private static int vitePortForSummary;

    private static void ensureAcceptanceFixtures(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path glbPath = directory.resolve("fixture.glb");
        if (!Files.exists(glbPath)) {
            String gltf = """
                    {"asset":{"version":"2.0","generator":"2Dto3D isolated acceptance fixture"},"scene":0,"scenes":[{"nodes":[0]}],"nodes":[{"name":"E2E body node","mesh":0}],"meshes":[{"name":"E2E double primitive mesh","primitives":[{"attributes":{"POSITION":0},"mode":4},{"attributes":{"POSITION":0},"mode":4}]}],"buffers":[{"byteLength":36}],"bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36,"target":34962}],"accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3","min":[-1,-1,0],"max":[1,1,0]}]}
                    """.strip();
            byte[] json = gltf.getBytes(StandardCharsets.UTF_8);
            int jsonLength = (json.length + 3) & ~3;
            int totalLength = 12 + 8 + jsonLength + 8 + 36;
            java.nio.ByteBuffer glb = java.nio.ByteBuffer.allocate(totalLength).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            glb.putInt(0x46546c67).putInt(2).putInt(totalLength);
            glb.putInt(jsonLength).putInt(0x4e4f534a).put(json);
            while (glb.position() < 20 + jsonLength) glb.put((byte) 0x20);
            glb.putInt(36).putInt(0x004e4942);
            float[][] vertices = {{-1f, -1f, 0f}, {1f, -1f, 0f}, {0f, 1f, 0f}};
            for (float[] vertex : vertices) for (float coordinate : vertex) glb.putFloat(coordinate);
            Files.write(glbPath, glb.array());
        }
        Path imagePath = directory.resolve("fixture.png");
        if (!Files.exists(imagePath)) {
            BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) image.setRGB(x, y, 0xfff0b450);
            if (!ImageIO.write(image, "png", imagePath.toFile())) throw new IOException("PNG fixture writer unavailable");
        }
    }
    private static Set<Path> meshSpools() throws IOException {
        try (var files = Files.list(MESH_TEMP)) {
            return files.filter(path -> path.getFileName().toString().startsWith("admin-mesh-request-"))
                    .collect(java.util.stream.Collectors.toSet());
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    }

    private static void prepareDisposablePostgres() throws Exception {
        postgres = new PostgreSQLContainer<>(IMAGE).withDatabaseName("admin3d_e2e_acceptance")
                .withUsername("postgres").withPassword("admin3d_e2e_bootstrap_test_only");
        postgres.start();
        DataSource root = new DriverManagerDataSource(postgres.getJdbcUrl(), "postgres",
                "admin3d_e2e_bootstrap_test_only");
        bootstrap = new JdbcTemplate(root);
        provisionRoles();
        Flyway.configure().dataSource(postgres.getJdbcUrl(), "admin3d_migrator", MIGRATOR_PASSWORD)
                .locations("classpath:db/migration").schemas("public").defaultSchema("public")
                .cleanDisabled(true).target(MigrationVersion.fromVersion("14")).load().migrate();
        bootstrap.execute("GRANT REFERENCES ON TABLE public.model_3d, public.media_asset, public.materials, public.users TO admin3d_owner");
        Flyway.configure().dataSource(postgres.getJdbcUrl(), "admin3d_migrator", MIGRATOR_PASSWORD)
                .locations("classpath:db/migration").schemas("public").defaultSchema("public")
                .cleanDisabled(true).load().migrate();
        Flyway.configure().dataSource(postgres.getJdbcUrl(), "admin3d_migrator", MIGRATOR_PASSWORD)
                .locations("classpath:db/migration").schemas("public").defaultSchema("public").load().validate();
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        adminId = insertUser("Admin E2E", ADMIN_EMAIL, encoder.encode(ADMIN_PASSWORD), "ADMIN");
        secondAdminId = insertUser("Admin E2E Concurrency", SECOND_ADMIN_EMAIL,
                encoder.encode(SECOND_ADMIN_PASSWORD), "ADMIN");
        userId = insertUser("User E2E", USER_EMAIL, encoder.encode(USER_PASSWORD), "USER");
        whiteColorId = bootstrap.queryForObject("INSERT INTO colors(name,hex_code) VALUES (?,?) RETURNING color_id",
                Integer.class, "E2E White", "#ffffff");
        blueColorId = bootstrap.queryForObject("INSERT INTO colors(name,hex_code) VALUES (?,?) RETURNING color_id",
                Integer.class, "E2E Blue", "#2255cc");
        materialId = bootstrap.queryForObject("""
                INSERT INTO materials(name,price_per_gram,stock_quantity,unit,is_available)
                VALUES ('E2E PLA',1.0,0,'g','AVAILABLE') RETURNING material_id
                """, Integer.class);
        bootstrap.update("INSERT INTO material_colors(material_id,color_id,quantity) VALUES (?,?,0)",
                materialId, whiteColorId);
        bootstrap.update("INSERT INTO material_colors(material_id,color_id,quantity) VALUES (?,?,0)",
                materialId, blueColorId);
    }

    private static int insertUser(String name, String email, String passwordHash, String role) {
        return bootstrap.queryForObject("""
                INSERT INTO users(name,email,password,role,status) VALUES (?,?,?,?,'ACTIVE') RETURNING user_id
                """, Integer.class, name, email, passwordHash, role);
    }

    private static void provisionRoles() {
        bootstrap.execute("CREATE ROLE admin3d_owner NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
        bootstrap.execute("CREATE ROLE admin3d_lock_owner NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
        bootstrap.execute("CREATE ROLE admin3d_command_owner NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
        bootstrap.execute("CREATE ROLE payos_trust_owner NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
        bootstrap.execute("CREATE ROLE admin3d_migrator LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '" + MIGRATOR_PASSWORD + "'");
        bootstrap.execute("CREATE ROLE app_runtime LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '" + RUNTIME_PASSWORD + "'");
        bootstrap.execute("GRANT admin3d_owner TO admin3d_migrator WITH INHERIT FALSE, SET TRUE");
        bootstrap.execute("GRANT admin3d_lock_owner TO admin3d_migrator WITH INHERIT FALSE, SET TRUE");
        bootstrap.execute("GRANT admin3d_command_owner TO admin3d_migrator WITH INHERIT FALSE, SET TRUE");
        bootstrap.execute("GRANT payos_trust_owner TO admin3d_migrator WITH INHERIT FALSE, SET TRUE");
        bootstrap.execute("GRANT CONNECT ON DATABASE admin3d_e2e_acceptance TO admin3d_migrator, app_runtime");
        bootstrap.execute("GRANT USAGE, CREATE ON SCHEMA public TO admin3d_owner");
        bootstrap.execute("GRANT USAGE, CREATE ON SCHEMA public TO admin3d_migrator WITH GRANT OPTION");
        bootstrap.execute("GRANT USAGE ON SCHEMA public TO app_runtime");
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private static JsonNode readJson(String value) {
        try { return JSON.readTree(value); }
        catch (IOException failure) { throw new IllegalStateException("Invalid JSON persisted by E2E", failure); }
    }

    private record StoredUpload(MediaKind kind, byte[] bytes, String sha256) { }
}
