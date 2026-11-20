package com.exe.morphia3d.controller;

import com.exe.morphia3d.Morphia3dApplication;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.Role;
import com.exe.morphia3d.security.JwtUtil;
import com.exe.morphia3d.security.UserPrincipal;
import com.exe.morphia3d.service.glb.GlbStructure;
import com.exe.morphia3d.enums.MediaKind;
import com.exe.morphia3d.enums.MediaProvider;
import com.exe.morphia3d.service.MediaStorageService;
import com.exe.morphia3d.service.MediaUploadIntent;
import com.exe.morphia3d.service.MediaUploadResult;
import com.exe.morphia3d.service.MediaStorageException;
import com.exe.morphia3d.service.ValidatedMedia;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Real servlet HTTP, production security chain, application services and PostgreSQL 16. */
@SpringBootTest(classes = Morphia3dApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.jpa.hibernate.ddl-auto=none",
                "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
                "spring.jpa.properties.hibernate.jdbc.lob.non_contextual_creation=true",
                "spring.jpa.open-in-view=false",
                "spring.flyway.enabled=false",
                "media.lifecycle.reconciliation-enabled=false",
                "app.features.admin-product-snapshot-upload.enabled=true",
                "cloudinary.cloud-name=test-cloud",
                "media.upload.snapshot-temp-directory=D:/2Dto3D/2Dto3D_BE/.qa-temp/ADMIN3D-BE-HTTP-01/snapshot-spool"
        })
@ActiveProfiles("test")
class AdminProductMeshRealHttpPostgresAcceptanceTest {
    private static final String IMAGE = "postgres@sha256:721873c34ceb9f8d8fc265984940dc982404c105f19ad51be9fdc5970a6080ea";
    private static final String MIGRATOR_PASSWORD = "admin3d_http_migrator_test_only";
    private static final String RUNTIME_PASSWORD = "admin3d_http_runtime_test_only";
    private static final Path SPOOL_DIRECTORY = Path.of(
            "D:/2Dto3D/2Dto3D_BE/.qa-temp/ADMIN3D-BE-HTTP-01/snapshot-spool");
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(IMAGE)
            .withDatabaseName("admin3d_real_http_test")
            .withUsername("postgres")
            .withPassword("admin3d_bootstrap_test_only");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static JdbcTemplate bootstrap;
    private static int adminId;
    private static int userId;
    private static int productId;
    private static int materialId;
    private static int colorId;
    private static long mediaAssetId;
    private static UUID snapshotId;

    static {
        try {
            prepareDisposablePostgresAndFixtures();
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @Autowired private JwtUtil jwt;
    @MockitoBean private MediaStorageService storage;
    @LocalServerPort private int port;
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private final List<StoredUpload> storedUploads = new ArrayList<>();

    @BeforeEach
    void prepareFakeStorageAndPrivateSpoolDirectory() throws Exception {
        Files.createDirectories(SPOOL_DIRECTORY);
        try (var entries = Files.list(SPOOL_DIRECTORY)) {
            assertThat(entries.toList()).isEmpty();
        }
        storedUploads.clear();
        reset(storage);
    }

    @AfterEach
    void verifySnapshotSpoolWasRemoved() throws Exception {
        try (var entries = Files.list(SPOOL_DIRECTORY)) {
            assertThat(entries.toList()).isEmpty();
        }
    }

    static void prepareDisposablePostgresAndFixtures() throws Exception {
        POSTGRES.start();
        DataSource root = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "postgres", "admin3d_bootstrap_test_only");
        bootstrap = new JdbcTemplate(root);
        provisionRoles();
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), "admin3d_migrator", MIGRATOR_PASSWORD)
                .locations("classpath:db/migration").schemas("public").defaultSchema("public")
                .cleanDisabled(true).target(MigrationVersion.fromVersion("14")).load().migrate();
        bootstrap.execute("GRANT REFERENCES ON TABLE public.model_3d, public.media_asset, public.materials, public.users TO admin3d_owner");
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), "admin3d_migrator", MIGRATOR_PASSWORD)
                .locations("classpath:db/migration").schemas("public").defaultSchema("public")
                .cleanDisabled(true).load().migrate();
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), "admin3d_migrator", MIGRATOR_PASSWORD)
                .locations("classpath:db/migration").schemas("public").defaultSchema("public").load().validate();
        bootstrap.update("INSERT INTO public.admin3d_publication_policy(singleton, cloud_name) VALUES (TRUE, ?)",
                "test-cloud");
        grantRuntimeReadAndHistoryInsert();

        adminId = insertUser("admin3d-admin@example.invalid", "ADMIN");
        userId = insertUser("admin3d-user@example.invalid", "USER");
        colorId = bootstrap.queryForObject(
                "INSERT INTO colors(name,hex_code) VALUES (?,?) RETURNING color_id",
                Integer.class, "acceptance-color", "#345678");
        materialId = bootstrap.queryForObject("""
                INSERT INTO materials(name,price_per_gram,stock_quantity,unit,is_available)
                VALUES ('acceptance-material',1.0,0,'g','AVAILABLE') RETURNING material_id
                """, Integer.class);
        bootstrap.update("INSERT INTO material_colors(material_id,color_id,quantity) VALUES (?,?,0)", materialId, colorId);
        UUID operationId = UUID.randomUUID();
        mediaAssetId = bootstrap.queryForObject("""
                INSERT INTO media_asset(operation_id,media_kind,provider,cloud_name,public_id,resource_type,
                    delivery_type,format,byte_count,state)
                VALUES (?,'MODEL_3D','CLOUDINARY','test-cloud','private-test-object','raw','upload','glb',64,'ATTACHED')
                RETURNING id
                """, Long.class, operationId);
        productId = bootstrap.queryForObject("""
                INSERT INTO model_3d(source_type,user_id,name,model_status,media_asset_id)
                VALUES ('DESIGNER_MODEL',?,'acceptance-product','ACTIVE',?) RETURNING model_id
                """, Integer.class, adminId, mediaAssetId);
        snapshotId = UUID.randomUUID();
        bootstrap.update("""
                INSERT INTO product_glb_structure_snapshots
                    (structure_snapshot_id,product_id,media_asset_id,snapshot_revision,parser_version,structure_json,created_by)
                VALUES (?,?,?,1,?,?::jsonb,?)
                """, snapshotId, productId, mediaAssetId, "glb-structure-1",
                JSON.writeValueAsString(validStructure()), adminId);
    }

    @AfterAll
    static void stopDisposablePostgres() {
        if (POSTGRES.isRunning()) POSTGRES.stop();
    }

    @DynamicPropertySource
    static void applicationDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "app_runtime");
        registry.add("spring.datasource.password", () -> RUNTIME_PASSWORD);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Test
    void realHttpSecurityAndPersistenceRoundTrip() throws Exception {
        String base = "http://127.0.0.1:" + port + "/api/admin/products/" + productId;
        HttpResponse<String> anonymous = exchange(base + "/mesh-configurations", HttpMethod.GET,
                new HttpEntity<>(null, new HttpHeaders()));
        evidence("anonymous-config-get", anonymous);
        assertThat(anonymous.statusCode()).isEqualTo(401);

        HttpResponse<String> nonAdmin = exchange(base + "/mesh-configurations",
                HttpMethod.GET, authenticated(userId, "admin3d-user@example.invalid", Role.USER, null));
        evidence("non-admin-config-get", nonAdmin);
        assertThat(nonAdmin.statusCode()).isEqualTo(403);

        HttpResponse<String> structure = exchange(base + "/mesh-structure",
                HttpMethod.GET, authenticated(adminId, "admin3d-admin@example.invalid", Role.ADMIN, null));
        evidence("admin-structure-get", structure);
        assertThat(structure.statusCode()).isEqualTo(200);
        assertThat(structure.body()).contains(snapshotId.toString())
                .doesNotContain("private-test-object", "cloudinary.com", "/raw/", "admin3d-admin@example.invalid");

        String body = JSON.writeValueAsString(Map.of(
                "mediaAssetId", mediaAssetId,
                "structureSnapshotId", snapshotId,
                "expectedRevision", 0,
                "materialId", materialId,
                "meshes", List.of(Map.of("nodeIndex", 0, "meshIndex", 0, "primitiveIndex", 0,
                        "customizable", true, "defaultColorId", colorId, "allowedColorIds", List.of(colorId)))));
        HttpResponse<String> put = exchange(base + "/mesh-configurations", HttpMethod.PUT,
                authenticated(adminId, "admin3d-admin@example.invalid", Role.ADMIN, body));
        evidence("admin-config-put-revision-1", put);
        assertThat(put.statusCode()).isEqualTo(200);
        assertThat(put.body()).contains("\"revision\":1").doesNotContain("private-test-object");

        HttpResponse<String> reloaded = exchange(base + "/mesh-configurations", HttpMethod.GET,
                authenticated(adminId, "admin3d-admin@example.invalid", Role.ADMIN, null));
        evidence("admin-config-reload", reloaded);
        assertThat(reloaded.statusCode()).isEqualTo(200);
        assertThat(reloaded.body()).contains("\"revision\":1");

        HttpResponse<String> stale = exchange(base + "/mesh-configurations", HttpMethod.PUT,
                authenticated(adminId, "admin3d-admin@example.invalid", Role.ADMIN, body));
        evidence("stale-revision-put", stale);
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(stale.body()).doesNotContain("private-test-object", "jdbc:", "admin3d-admin@example.invalid");

        HttpResponse<String> missingProduct = exchange("http://127.0.0.1:" + port
                        + "/api/admin/products/2147483000/mesh-configurations", HttpMethod.GET,
                authenticated(adminId, "admin3d-admin@example.invalid", Role.ADMIN, null));
        evidence("missing-product-get", missingProduct);
        assertThat(missingProduct.statusCode()).isEqualTo(404);

        String missingMaterialBody = body.replace("\"expectedRevision\":0", "\"expectedRevision\":1")
                .replace("\"materialId\":" + materialId, "\"materialId\":2147483000");
        HttpResponse<String> missingMaterial = exchange(base + "/mesh-configurations", HttpMethod.PUT,
                authenticated(adminId, "admin3d-admin@example.invalid", Role.ADMIN, missingMaterialBody));
        evidence("missing-material-put", missingMaterial);
        assertThat(missingMaterial.statusCode()).isEqualTo(422);
        assertThat(bootstrap.queryForObject("SELECT count(*) FROM product_mesh_configuration_revisions WHERE product_id=?",
                Integer.class, productId)).isEqualTo(1);

        String concurrentBody = body.replace("\"expectedRevision\":0", "\"expectedRevision\":1");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = pool.submit(() -> concurrentPut(base, concurrentBody, ready, start));
            Future<Integer> second = pool.submit(() -> concurrentPut(base, concurrentBody, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Integer> statuses = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            System.out.println("HTTP_EVIDENCE concurrent-same-revision statuses=" + statuses);
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
        assertThat(bootstrap.queryForObject("SELECT count(*) FROM product_mesh_configuration_revisions WHERE product_id=?",
                Integer.class, productId)).isEqualTo(2);

        UUID replacementSnapshot = UUID.randomUUID();
        bootstrap.update("""
                INSERT INTO product_glb_structure_snapshots
                    (structure_snapshot_id,product_id,media_asset_id,snapshot_revision,parser_version,structure_json,created_by)
                VALUES (?,?,?,2,?,?::jsonb,?)
                """, replacementSnapshot, productId, mediaAssetId, "glb-structure-1",
                JSON.writeValueAsString(validStructure()), adminId);
        String staleSnapshotBody = concurrentBody.replace("\"expectedRevision\":1", "\"expectedRevision\":2");
        HttpResponse<String> staleSnapshot = exchange(base + "/mesh-configurations", HttpMethod.PUT,
                authenticated(adminId, "admin3d-admin@example.invalid", Role.ADMIN, staleSnapshotBody));
        evidence("stale-snapshot-put", staleSnapshot);
        assertThat(staleSnapshot.statusCode()).isEqualTo(409);

        long replacementAssetId = bootstrap.queryForObject("""
                INSERT INTO media_asset(operation_id,media_kind,provider,cloud_name,public_id,resource_type,
                    delivery_type,format,byte_count,state)
                VALUES (?,'MODEL_3D','CLOUDINARY','test-cloud','replacement-object','raw','upload','glb',64,'ATTACHED')
                RETURNING id
                """, Long.class, UUID.randomUUID());
        bootstrap.update("UPDATE model_3d SET media_asset_id=? WHERE model_id=?", replacementAssetId, productId);
        String staleMediaBody = concurrentBody.replace("\"expectedRevision\":1", "\"expectedRevision\":2");
        HttpResponse<String> staleMedia = exchange(base + "/mesh-configurations", HttpMethod.PUT,
                authenticated(adminId, "admin3d-admin@example.invalid", Role.ADMIN, staleMediaBody));
        evidence("stale-media-put", staleMedia);
        assertThat(staleMedia.statusCode()).isEqualTo(409);
        assertThat(staleMedia.body()).doesNotContain("replacement-object", "jdbc:", "admin3d-admin@example.invalid");
    }

    @Test
    void restrictedProductCommandsPreservePublicationValidationAndRuntimePrivileges() throws Exception {
        assertThat(bootstrap.queryForObject(
                "SELECT has_table_privilege('app_runtime','public.model_3d','UPDATE')", Boolean.class)).isFalse();
        assertThat(bootstrap.queryForObject(
                "SELECT has_any_column_privilege('app_runtime','public.model_3d','UPDATE')", Boolean.class)).isFalse();
        assertThat(bootstrap.queryForObject(
                "SELECT pg_has_role('app_runtime','admin3d_command_owner','MEMBER')", Boolean.class)).isFalse();
        assertThat(bootstrap.queryForObject(
                "SELECT has_schema_privilege('app_runtime','public','CREATE')", Boolean.class)).isFalse();
        assertThat(bootstrap.queryForObject("SELECT has_function_privilege('app_runtime', "
                + "'public.admin3d_publish_designer_model(integer)','EXECUTE')", Boolean.class)).isTrue();
        assertThat(bootstrap.queryForObject("SELECT has_function_privilege('app_runtime', "
                + "'public.admin3d_edit_designer_model(integer,boolean,text,boolean,text,boolean,text)','EXECUTE')",
                Boolean.class)).isTrue();
        assertThat(bootstrap.queryForObject("SELECT has_function_privilege('app_runtime', "
                + "'public.admin3d_archive_designer_model(integer)','EXECUTE')", Boolean.class)).isTrue();
        assertThat(bootstrap.queryForObject("SELECT has_function_privilege('public', "
                + "'public.admin3d_publish_designer_model(integer)','EXECUTE')", Boolean.class)).isFalse();

        DataSource runtimeSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_runtime", RUNTIME_PASSWORD);
        JdbcTemplate runtime = new JdbcTemplate(runtimeSource);
        assertThat(runtime.queryForObject("SELECT current_user", String.class)).isEqualTo("app_runtime");
        assertThatThrownBy(() -> runtime.update("UPDATE public.model_3d SET name='forbidden' WHERE model_id=?", productId))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasRootCauseInstanceOf(java.sql.SQLException.class);

        int publishable = insertPublishableProduct("Restricted publish draft");
        long snapshotsBeforeCommands = bootstrap.queryForObject(
                "SELECT count(*) FROM product_glb_structure_snapshots WHERE product_id=?", Long.class, publishable);
        long configurationsBeforeCommands = bootstrap.queryForObject(
                "SELECT count(*) FROM product_mesh_configuration_revisions WHERE product_id=?", Long.class, publishable);
        int imagesBefore = bootstrap.queryForObject("SELECT count(*) FROM model_images WHERE model_id=?",
                Integer.class, publishable);
        int mediaBefore = bootstrap.queryForObject("SELECT count(*) FROM media_asset WHERE operation_id=(SELECT operation_id FROM model_3d m JOIN media_asset a ON a.id=m.media_asset_id WHERE m.model_id=?)",
                Integer.class, publishable);
        String base = "http://127.0.0.1:" + port + "/api/admin/products/" + publishable;
        HttpResponse<String> anonymous = exchange(base + "/publish", HttpMethod.POST,
                new HttpEntity<>(null, new HttpHeaders()));
        evidence("restricted-publish-anonymous", anonymous);
        assertThat(anonymous.statusCode()).isEqualTo(401);
        HttpResponse<String> forbidden = exchange(base + "/publish", HttpMethod.POST,
                authenticated(userId, "admin3d-user@example.invalid", Role.USER, null));
        evidence("restricted-publish-non-admin", forbidden);
        assertThat(forbidden.statusCode()).isEqualTo(403);
        assertThat(bootstrap.queryForObject("SELECT model_status FROM model_3d WHERE model_id=?",
                String.class, publishable)).isEqualTo("PENDING");

        HttpResponse<String> publish = exchange(base + "/publish", HttpMethod.POST,
                authenticated(adminId, "admin3d-admin@example.invalid", Role.ADMIN, null));
        evidence("restricted-publish-valid", publish);
        assertThat(publish.statusCode()).isEqualTo(204);
        assertThat(bootstrap.queryForObject("SELECT model_status FROM model_3d WHERE model_id=?",
                String.class, publishable)).isEqualTo("ACTIVE");
        HttpResponse<String> repeatedPublish = exchange(base + "/publish", HttpMethod.POST,
                authenticated(adminId, "admin3d-admin@example.invalid", Role.ADMIN, null));
        assertThat(repeatedPublish.statusCode()).isEqualTo(204);
        assertThat(bootstrap.queryForObject("SELECT count(*) FROM media_asset WHERE operation_id=(SELECT operation_id FROM model_3d m JOIN media_asset a ON a.id=m.media_asset_id WHERE m.model_id=?)",
                Integer.class, publishable)).isEqualTo(mediaBefore);
        HttpResponse<String> publicCatalog = exchange("http://127.0.0.1:" + port + "/api/products/" + publishable,
                HttpMethod.GET, new HttpEntity<>(null, new HttpHeaders()));
        assertThat(publicCatalog.statusCode()).isEqualTo(200);
        assertThatThrownBy(() -> runtime.update("UPDATE public.model_3d SET name='direct runtime mutation' WHERE model_id=?",
                publishable)).isInstanceOf(org.springframework.dao.DataAccessException.class);

        HttpResponse<String> partialPatch = exchange("http://127.0.0.1:" + port + "/api/admin/products/" + publishable,
                HttpMethod.PATCH, authenticated(adminId, "admin3d-admin@example.invalid", Role.ADMIN,
                        "{\"description\":\"  retained metadata  \"}"));
        evidence("restricted-partial-patch", partialPatch);
        assertThat(partialPatch.statusCode()).isEqualTo(200);
        HttpResponse<String> namePatch = exchange("http://127.0.0.1:" + port + "/api/admin/products/" + publishable,
                HttpMethod.PATCH, authenticated(adminId, "admin3d-admin@example.invalid", Role.ADMIN,
                        "{\"name\":\"  Renamed restricted product  \"}"));
        HttpResponse<String> categoryPatch = exchange("http://127.0.0.1:" + port + "/api/admin/products/" + publishable,
                HttpMethod.PATCH, authenticated(adminId, "admin3d-admin@example.invalid", Role.ADMIN,
                        "{\"category\":\"  retained-category  \"}"));
        evidence("restricted-name-patch", namePatch);
        evidence("restricted-category-patch", categoryPatch);
        assertThat(namePatch.statusCode()).isEqualTo(200);
        assertThat(categoryPatch.statusCode()).isEqualTo(200);
        assertThat(bootstrap.queryForMap("SELECT name,description,category,file_path_3d FROM model_3d WHERE model_id=?",
                publishable)).containsEntry("name", "Renamed restricted product")
                .containsEntry("description", "retained metadata")
                .containsEntry("category", "retained-category");

        HttpResponse<String> emptyName = exchange("http://127.0.0.1:" + port + "/api/admin/products/" + publishable,
                HttpMethod.PATCH, authenticated(adminId, "admin3d-admin@example.invalid", Role.ADMIN,
                        "{\"name\":\"   \"}"));
        HttpResponse<String> longName = exchange("http://127.0.0.1:" + port + "/api/admin/products/" + publishable,
                HttpMethod.PATCH, authenticated(adminId, "admin3d-admin@example.invalid", Role.ADMIN,
                        JSON.writeValueAsString(Map.of("name", "x".repeat(151)))));
        evidence("restricted-empty-name", emptyName);
        evidence("restricted-long-name", longName);
        assertThat(emptyName.statusCode()).isEqualTo(400);
        assertThat(longName.statusCode()).isEqualTo(400);
        int wrongType = bootstrap.queryForObject("""
                INSERT INTO model_3d(source_type,user_id,name,model_status)
                VALUES ('AI_MODEL',?,'wrong type','PENDING') RETURNING model_id
                """, Integer.class, adminId);
        HttpResponse<String> missingPatch = exchange("http://127.0.0.1:" + port
                        + "/api/admin/products/2147483000", HttpMethod.PATCH,
                authenticated(adminId, "admin3d-admin@example.invalid", Role.ADMIN, "{\"name\":\"x\"}"));
        HttpResponse<String> wrongTypePatch = exchange("http://127.0.0.1:" + port + "/api/admin/products/"
                        + wrongType, HttpMethod.PATCH,
                authenticated(adminId, "admin3d-admin@example.invalid", Role.ADMIN, "{\"name\":\"x\"}"));
        HttpResponse<String> missingArchive = exchange("http://127.0.0.1:" + port
                        + "/api/admin/products/2147483000/archive", HttpMethod.POST,
                authenticated(adminId, "admin3d-admin@example.invalid", Role.ADMIN, null));
        HttpResponse<String> wrongTypeArchive = exchange("http://127.0.0.1:" + port + "/api/admin/products/"
                        + wrongType + "/archive", HttpMethod.POST,
                authenticated(adminId, "admin3d-admin@example.invalid", Role.ADMIN, null));
        assertThat(missingPatch.statusCode()).isEqualTo(404);
        assertThat(wrongTypePatch.statusCode()).isEqualTo(404);
        assertThat(missingArchive.statusCode()).isEqualTo(404);
        assertThat(wrongTypeArchive.statusCode()).isEqualTo(404);

        HttpResponse<String> archive = exchange(base + "/archive", HttpMethod.POST,
                authenticated(adminId, "admin3d-admin@example.invalid", Role.ADMIN, null));
        HttpResponse<String> archiveAgain = exchange(base + "/archive", HttpMethod.POST,
                authenticated(adminId, "admin3d-admin@example.invalid", Role.ADMIN, null));
        assertThat(archive.statusCode()).isEqualTo(204);
        assertThat(archiveAgain.statusCode()).isEqualTo(204);
        assertThat(bootstrap.queryForObject("SELECT model_status FROM model_3d WHERE model_id=?",
                String.class, publishable)).isEqualTo("INACTIVE");
        assertThat(bootstrap.queryForObject("SELECT count(*) FROM model_images WHERE model_id=?",
                Integer.class, publishable)).isEqualTo(imagesBefore);
        assertThat(bootstrap.queryForObject("SELECT count(*) FROM media_asset WHERE operation_id=(SELECT operation_id FROM model_3d m JOIN media_asset a ON a.id=m.media_asset_id WHERE m.model_id=?)",
                Integer.class, publishable)).isEqualTo(mediaBefore);
        assertThat(bootstrap.queryForObject("SELECT count(*) FROM product_glb_structure_snapshots WHERE product_id=?",
                Long.class, publishable)).isEqualTo(snapshotsBeforeCommands);
        assertThat(bootstrap.queryForObject("SELECT count(*) FROM product_mesh_configuration_revisions WHERE product_id=?",
                Long.class, publishable)).isEqualTo(configurationsBeforeCommands);
        verifyNoInteractions(storage);
    }

    @Test
    void restrictedPublishFailsClosedForInvalidTargetsAndDirectCalls() throws Exception {
        int noImagesProduct = insertPublishableProduct("Missing image draft");
        long snapshotsBefore = bootstrap.queryForObject(
                "SELECT count(*) FROM product_glb_structure_snapshots WHERE product_id=?", Long.class, noImagesProduct);
        bootstrap.update("DELETE FROM model_images WHERE model_id=?", noImagesProduct);
        String noImagesBase = "http://127.0.0.1:" + port + "/api/admin/products/" + noImagesProduct;
        HttpResponse<String> ineligible = exchange(noImagesBase + "/publish", HttpMethod.POST,
                authenticated(adminId, "admin3d-admin@example.invalid", Role.ADMIN, null));
        evidence("restricted-publish-missing-image", ineligible);
        assertThat(ineligible.statusCode()).isEqualTo(409);
        assertThat(bootstrap.queryForObject("SELECT model_status FROM model_3d WHERE model_id=?",
                String.class, noImagesProduct)).isEqualTo("PENDING");
        assertThat(bootstrap.queryForObject("SELECT count(*) FROM product_glb_structure_snapshots WHERE product_id=?",
                Long.class, noImagesProduct)).isEqualTo(snapshotsBefore);
        assertThat(bootstrap.queryForObject("SELECT count(*) FROM model_images WHERE model_id=?",
                Long.class, noImagesProduct)).isZero();

        int wrongTypeProduct = bootstrap.queryForObject("""
                INSERT INTO model_3d(source_type,user_id,name,model_status)
                VALUES ('AI_MODEL',?,'not a designer product','PENDING') RETURNING model_id
                """, Integer.class, adminId);
        HttpResponse<String> wrongType = exchange("http://127.0.0.1:" + port
                        + "/api/admin/products/" + wrongTypeProduct + "/publish", HttpMethod.POST,
                authenticated(adminId, "admin3d-admin@example.invalid", Role.ADMIN, null));
        evidence("restricted-publish-wrong-type", wrongType);
        assertThat(wrongType.statusCode()).isEqualTo(404);
        assertThat(bootstrap.queryForObject("SELECT model_status FROM model_3d WHERE model_id=?",
                String.class, wrongTypeProduct)).isEqualTo("PENDING");
        HttpResponse<String> missing = exchange("http://127.0.0.1:" + port
                        + "/api/admin/products/2147483000/publish", HttpMethod.POST,
                authenticated(adminId, "admin3d-admin@example.invalid", Role.ADMIN, null));
        assertThat(missing.statusCode()).isEqualTo(404);

        DataSource runtimeSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_runtime", RUNTIME_PASSWORD);
        JdbcTemplate runtime = new JdbcTemplate(runtimeSource);
        assertThatThrownBy(() -> runtime.queryForObject(
                "SELECT public.admin3d_publish_designer_model(0)", Integer.class))
                .satisfies(failure -> assertThat(sqlState(failure)).isEqualTo("22023"));
        assertThatThrownBy(() -> runtime.queryForObject("""
                SELECT public.admin3d_edit_designer_model(?,TRUE,'   ',FALSE,'',FALSE,'')
                """, Integer.class, noImagesProduct))
                .satisfies(failure -> assertThat(sqlState(failure)).isEqualTo("22023"));
        assertThatThrownBy(() -> runtime.execute("SET ROLE admin3d_command_owner"))
                .satisfies(failure -> assertThat(sqlState(failure)).isEqualTo("42501"));
        assertThatThrownBy(() -> runtime.queryForObject(
                "SELECT count(*) FROM public.admin3d_publication_policy", Long.class))
                .satisfies(failure -> assertThat(sqlState(failure)).isEqualTo("42501"));

        long publicExecuteGrants = bootstrap.queryForObject("""
                SELECT count(*) FROM pg_catalog.pg_proc AS procedure
                CROSS JOIN LATERAL pg_catalog.aclexplode(COALESCE(procedure.proacl,
                    pg_catalog.acldefault('f', procedure.proowner))) AS privilege
                WHERE procedure.oid IN (
                    'public.admin3d_publish_designer_model(integer)'::regprocedure,
                    'public.admin3d_edit_designer_model(integer,boolean,text,boolean,text,boolean,text)'::regprocedure,
                    'public.admin3d_archive_designer_model(integer)'::regprocedure)
                  AND privilege.grantee = 0 AND privilege.privilege_type = 'EXECUTE'
                """, Long.class);
        assertThat(publicExecuteGrants).isZero();
        verifyNoInteractions(storage);
    }

    @Test
    void v17OwnerPolicyAndProviderIdentityBoundariesFailClosed() throws Exception {
        assertThat(bootstrap.queryForMap("""
                SELECT owner_role.rolname AS table_owner, owner_role.rolcanlogin AS owner_can_login,
                       owner_role.rolinherit AS owner_inherits,
                       command_role.rolname AS publish_owner
                FROM pg_catalog.pg_class policy
                JOIN pg_catalog.pg_roles owner_role ON owner_role.oid=policy.relowner
                JOIN pg_catalog.pg_proc publish_fn
                  ON publish_fn.oid='public.admin3d_publish_designer_model(integer)'::regprocedure
                JOIN pg_catalog.pg_roles command_role ON command_role.oid=publish_fn.proowner
                WHERE policy.oid='public.admin3d_publication_policy'::regclass
                """))
                .containsEntry("table_owner", "admin3d_command_owner")
                .containsEntry("owner_can_login", false)
                .containsEntry("owner_inherits", false)
                .containsEntry("publish_owner", "admin3d_command_owner");
        assertThat(bootstrap.queryForObject("""
                SELECT count(*) FROM pg_catalog.pg_roles
                WHERE rolname IN ('admin3d_command_owner','admin3d_lock_owner')
                  AND (rolcanlogin OR rolinherit OR rolsuper OR rolcreatedb OR rolcreaterole
                       OR rolreplication OR rolbypassrls)
                """, Integer.class)).isZero();
        assertThat(bootstrap.queryForObject("""
                SELECT count(*) FROM (VALUES
                    ('SELECT', has_table_privilege('app_runtime','public.admin3d_publication_policy','SELECT')),
                    ('INSERT', has_table_privilege('app_runtime','public.admin3d_publication_policy','INSERT')),
                    ('UPDATE', has_table_privilege('app_runtime','public.admin3d_publication_policy','UPDATE')),
                    ('DELETE', has_table_privilege('app_runtime','public.admin3d_publication_policy','DELETE'))
                ) AS privileges(name, allowed) WHERE allowed
                """, Integer.class)).isZero();
        assertThat(bootstrap.queryForObject("""
                SELECT count(*) FROM pg_catalog.pg_proc AS procedure
                CROSS JOIN LATERAL pg_catalog.aclexplode(COALESCE(procedure.proacl,
                    pg_catalog.acldefault('f', procedure.proowner))) AS privilege
                WHERE procedure.oid IN (
                    'public.admin3d_publish_designer_model(integer)'::regprocedure,
                    'public.admin3d_edit_designer_model(integer,boolean,text,boolean,text,boolean,text)'::regprocedure,
                    'public.admin3d_archive_designer_model(integer)'::regprocedure)
                  AND privilege.grantee = 0 AND privilege.privilege_type = 'EXECUTE'
                """, Integer.class)).isZero();

        JdbcTemplate runtime = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "app_runtime", RUNTIME_PASSWORD));
        assertThat(runtime.queryForObject("SELECT current_user", String.class)).isEqualTo("app_runtime");
        assertThatThrownBy(() -> runtime.update(
                "INSERT INTO public.admin3d_publication_policy(singleton,cloud_name) VALUES (TRUE,'x')"))
                .satisfies(failure -> assertThat(sqlState(failure)).isEqualTo("42501"));
        assertThatThrownBy(() -> runtime.update(
                "UPDATE public.admin3d_publication_policy SET cloud_name='x' WHERE singleton=TRUE"))
                .satisfies(failure -> assertThat(sqlState(failure)).isEqualTo("42501"));
        int directSqlInvalid = insertPublishableProduct("Direct SQL invalid publish draft");
        bootstrap.update("DELETE FROM model_images WHERE model_id=?", directSqlInvalid);
        assertThatThrownBy(() -> runtime.queryForObject(
                "SELECT public.admin3d_publish_designer_model(?)", Integer.class, directSqlInvalid))
                .satisfies(failure -> assertThat(sqlState(failure)).isEqualTo("P0001"));
        assertThat(bootstrap.queryForObject("SELECT model_status FROM model_3d WHERE model_id=?",
                String.class, directSqlInvalid)).isEqualTo("PENDING");

        int noPolicy = insertPublishableProduct("No policy draft");
        bootstrap.update("DELETE FROM public.admin3d_publication_policy WHERE singleton=TRUE");
        String base = "http://127.0.0.1:" + port + "/api/admin/products/" + noPolicy + "/publish";
        HttpResponse<String> missingPolicy = exchange(base, HttpMethod.POST,
                authenticated(adminId, "admin3d-admin@example.invalid", Role.ADMIN, null));
        evidence("v17-missing-policy", missingPolicy);
        assertThat(missingPolicy.statusCode()).isEqualTo(409);
        assertThat(bootstrap.queryForObject("SELECT model_status FROM model_3d WHERE model_id=?",
                String.class, noPolicy)).isEqualTo("PENDING");

        bootstrap.update("INSERT INTO public.admin3d_publication_policy(singleton,cloud_name) VALUES (TRUE,'wrong-cloud')");
        int wrongPolicyCloud = insertPublishableProduct("Wrong policy cloud draft");
        HttpResponse<String> cloudMismatch = exchange("http://127.0.0.1:" + port + "/api/admin/products/"
                        + wrongPolicyCloud + "/publish", HttpMethod.POST,
                authenticated(adminId, "admin3d-admin@example.invalid", Role.ADMIN, null));
        evidence("v17-policy-cloud-mismatch", cloudMismatch);
        assertThat(cloudMismatch.statusCode()).isEqualTo(409);

        bootstrap.update("UPDATE public.admin3d_publication_policy SET cloud_name='test-cloud' WHERE singleton=TRUE");
        List<String> invalidAssetUpdates = List.of(
                "UPDATE media_asset SET provider='LOCAL' WHERE id=?",
                "UPDATE media_asset SET provider_asset_id=NULL WHERE id=?",
                "UPDATE media_asset SET secure_url='https://invalid.example/private.glb' WHERE id=?",
                "UPDATE media_asset SET provider_version=NULL WHERE id=?",
                "UPDATE media_asset SET resource_type='image' WHERE id=?",
                "UPDATE media_asset SET delivery_type='private' WHERE id=?",
                "UPDATE media_asset SET state='READY' WHERE id=?",
                "UPDATE media_asset SET cloud_name='other-cloud' WHERE id=?",
                "UPDATE media_asset SET public_id='provider/foreign-object' WHERE id=?");
        for (int index = 0; index < invalidAssetUpdates.size(); index++) {
            int product = insertPublishableProduct("Invalid media identity " + index);
            long assetId = bootstrap.queryForObject("SELECT media_asset_id FROM model_3d WHERE model_id=?",
                    Long.class, product);
            bootstrap.update(invalidAssetUpdates.get(index), assetId);
            HttpResponse<String> response = exchange("http://127.0.0.1:" + port + "/api/admin/products/"
                            + product + "/publish", HttpMethod.POST,
                    authenticated(adminId, "admin3d-admin@example.invalid", Role.ADMIN, null));
            evidence("v17-invalid-media-identity-" + index, response);
            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(bootstrap.queryForObject("SELECT model_status FROM model_3d WHERE model_id=?",
                    String.class, product)).isEqualTo("PENDING");
        }

        List<String> invalidImageUpdates = List.of(
                "UPDATE media_asset SET provider='LOCAL' WHERE id=?",
                "UPDATE media_asset SET provider_asset_id=NULL WHERE id=?",
                "UPDATE media_asset SET secure_url='https://invalid.example/private.png' WHERE id=?",
                "UPDATE media_asset SET provider_version=NULL WHERE id=?",
                "UPDATE media_asset SET resource_type='raw' WHERE id=?",
                "UPDATE media_asset SET delivery_type='private' WHERE id=?",
                "UPDATE media_asset SET format='glb' WHERE id=?",
                "UPDATE media_asset SET state='READY' WHERE id=?",
                "UPDATE media_asset SET cloud_name='other-cloud' WHERE id=?",
                "UPDATE media_asset SET public_id='provider/foreign-image' WHERE id=?");
        for (int index = 0; index < invalidImageUpdates.size(); index++) {
            int product = insertPublishableProduct("Invalid image identity " + index);
            long imageAssetId = bootstrap.queryForObject(
                    "SELECT media_asset_id FROM model_images WHERE model_id=?", Long.class, product);
            bootstrap.update(invalidImageUpdates.get(index), imageAssetId);
            HttpResponse<String> response = exchange("http://127.0.0.1:" + port + "/api/admin/products/"
                            + product + "/publish", HttpMethod.POST,
                    authenticated(adminId, "admin3d-admin@example.invalid", Role.ADMIN, null));
            evidence("v17-invalid-image-identity-" + index, response);
            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(bootstrap.queryForObject("SELECT model_status FROM model_3d WHERE model_id=?",
                    String.class, product)).isEqualTo("PENDING");
        }

        bootstrap.update("UPDATE public.admin3d_publication_policy SET cloud_name='test-cloud' WHERE singleton=TRUE");
        int wrongPrimary = insertPublishableProduct("Wrong primary image draft");
        bootstrap.update("UPDATE model_images SET is_primary=FALSE WHERE model_id=?", wrongPrimary);
        HttpResponse<String> wrongPrimaryResponse = exchange("http://127.0.0.1:" + port
                        + "/api/admin/products/" + wrongPrimary + "/publish", HttpMethod.POST,
                authenticated(adminId, "admin3d-admin@example.invalid", Role.ADMIN, null));
        evidence("v17-wrong-primary-image", wrongPrimaryResponse);
        assertThat(wrongPrimaryResponse.statusCode()).isEqualTo(409);

        int unattachedImage = insertPublishableProduct("Wrong operation image draft");
        UUID otherOperation = UUID.randomUUID();
        long otherImageAsset = insertPublishableAsset(otherOperation, "IMAGE", "image", "png");
        String otherImagePublicId = "product-media/images/" + otherOperation + "/" + otherImageAsset;
        bootstrap.update("UPDATE media_asset SET public_id=?,secure_url=? WHERE id=?", otherImagePublicId,
                "https://res.cloudinary.com/test-cloud/image/upload/v1/" + otherImagePublicId + ".png",
                otherImageAsset);
        bootstrap.update("UPDATE model_images SET image_path=?,media_asset_id=? WHERE model_id=?",
                otherImagePublicId, otherImageAsset, unattachedImage);
        HttpResponse<String> unattachedResponse = exchange("http://127.0.0.1:" + port
                        + "/api/admin/products/" + unattachedImage + "/publish", HttpMethod.POST,
                authenticated(adminId, "admin3d-admin@example.invalid", Role.ADMIN, null));
        evidence("v17-unattached-image-media", unattachedResponse);
        assertThat(unattachedResponse.statusCode()).isEqualTo(409);

        int pending = insertPublishableProduct("Pending public visibility draft");
        HttpResponse<String> pendingPublicRead = exchange("http://127.0.0.1:" + port + "/api/products/" + pending,
                HttpMethod.GET, new HttpEntity<>(null, new HttpHeaders()));
        evidence("v17-pending-public-product", pendingPublicRead);
        assertThat(pendingPublicRead.statusCode()).isEqualTo(404);
        verifyNoInteractions(storage);
    }

    @Test
    void v17CommandsSerializeBehindExplicitProductRowLockBarriers() throws Exception {
        DataSource writerSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "postgres", "admin3d_bootstrap_test_only");
        String base = "http://127.0.0.1:" + port + "/api/admin/products/";
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            int publishArchiveProduct = insertPublishableProduct("Barrier publish archive draft");
            try (Connection blocker = writerSource.getConnection()) {
                blocker.setAutoCommit(false);
                try (var lock = blocker.prepareStatement("SELECT model_id FROM model_3d WHERE model_id=? FOR UPDATE")) {
                    lock.setInt(1, publishArchiveProduct);
                    try (var rows = lock.executeQuery()) { assertThat(rows.next()).isTrue(); }
                }
                Future<HttpResponse<String>> archive = pool.submit(() -> exchange(base + publishArchiveProduct
                                + "/archive", HttpMethod.POST,
                        authenticated(adminId, "admin3d-admin@example.invalid", Role.ADMIN, null)));
                assertThat(awaitDatabaseWait("admin3d_archive_designer_model", 1))
                        .as("archive is blocked by the explicit product row lock").isTrue();
                Future<HttpResponse<String>> publish = pool.submit(() -> exchange(base + publishArchiveProduct
                                + "/publish", HttpMethod.POST,
                        authenticated(adminId, "admin3d-admin@example.invalid", Role.ADMIN, null)));
                assertThat(awaitDatabaseWait("admin3d_publish_designer_model", 1))
                        .as("publish is blocked by the explicit product row lock").isTrue();
                blocker.commit();
                HttpResponse<String> archived = archive.get(10, TimeUnit.SECONDS);
                HttpResponse<String> published = publish.get(10, TimeUnit.SECONDS);
                evidence("v17-barrier-archive", archived);
                evidence("v17-barrier-publish", published);
                assertThat(archived.statusCode()).isEqualTo(204);
                assertThat(published.statusCode()).isIn(204, 409);
                assertThat(bootstrap.queryForObject("SELECT model_status FROM model_3d WHERE model_id=?",
                        String.class, publishArchiveProduct)).isEqualTo("INACTIVE");
            }

            int editPublishProduct = insertPublishableProduct("Barrier edit publish draft");
            try (Connection blocker = writerSource.getConnection()) {
                blocker.setAutoCommit(false);
                try (var lock = blocker.prepareStatement("SELECT model_id FROM model_3d WHERE model_id=? FOR UPDATE")) {
                    lock.setInt(1, editPublishProduct);
                    try (var rows = lock.executeQuery()) { assertThat(rows.next()).isTrue(); }
                }
                Future<HttpResponse<String>> edit = pool.submit(() -> exchange(base + editPublishProduct,
                        HttpMethod.PATCH, authenticated(adminId, "admin3d-admin@example.invalid", Role.ADMIN,
                                "{\"name\":\"Barrier edited product\"}")));
                assertThat(awaitDatabaseWait("admin3d_edit_designer_model", 1))
                        .as("edit is blocked by the explicit product row lock").isTrue();
                Future<HttpResponse<String>> publish = pool.submit(() -> exchange(base + editPublishProduct
                                + "/publish", HttpMethod.POST,
                        authenticated(adminId, "admin3d-admin@example.invalid", Role.ADMIN, null)));
                assertThat(awaitDatabaseWait("admin3d_publish_designer_model", 1))
                        .as("publish is blocked by the explicit product row lock").isTrue();
                blocker.commit();
                HttpResponse<String> edited = edit.get(10, TimeUnit.SECONDS);
                HttpResponse<String> published = publish.get(10, TimeUnit.SECONDS);
                evidence("v17-barrier-edit", edited);
                evidence("v17-barrier-edit-publish", published);
                assertThat(edited.statusCode()).isEqualTo(200);
                assertThat(published.statusCode()).isEqualTo(204);
                assertThat(bootstrap.queryForMap("SELECT name,model_status FROM model_3d WHERE model_id=?",
                        editPublishProduct)).containsEntry("name", "Barrier edited product")
                        .containsEntry("model_status", "ACTIVE");
            }

            int archiveMeshProduct = insertPublishableProduct("Barrier archive mesh draft");
            long assetId = bootstrap.queryForObject("SELECT media_asset_id FROM model_3d WHERE model_id=?",
                    Long.class, archiveMeshProduct);
            UUID snapshot = bootstrap.queryForObject("""
                    SELECT structure_snapshot_id FROM product_glb_structure_snapshots
                    WHERE product_id=? ORDER BY snapshot_revision DESC LIMIT 1
                    """, UUID.class, archiveMeshProduct);
            long snapshotsBefore = bootstrap.queryForObject(
                    "SELECT count(*) FROM product_glb_structure_snapshots WHERE product_id=?",
                    Long.class, archiveMeshProduct);
            String meshBody = JSON.writeValueAsString(Map.of("mediaAssetId", assetId,
                    "structureSnapshotId", snapshot, "expectedRevision", 0, "materialId", materialId,
                    "meshes", List.of(Map.of("nodeIndex", 0, "meshIndex", 0, "primitiveIndex", 0,
                            "customizable", true, "defaultColorId", colorId, "allowedColorIds", List.of(colorId)))));
            try (Connection blocker = writerSource.getConnection()) {
                blocker.setAutoCommit(false);
                try (var lock = blocker.prepareStatement("SELECT model_id FROM model_3d WHERE model_id=? FOR UPDATE")) {
                    lock.setInt(1, archiveMeshProduct);
                    try (var rows = lock.executeQuery()) { assertThat(rows.next()).isTrue(); }
                }
                Future<HttpResponse<String>> archive = pool.submit(() -> exchange(base + archiveMeshProduct
                                + "/archive", HttpMethod.POST,
                        authenticated(adminId, "admin3d-admin@example.invalid", Role.ADMIN, null)));
                assertThat(awaitDatabaseWait("admin3d_archive_designer_model", 1))
                        .as("archive is blocked before the mesh update").isTrue();
                Future<HttpResponse<String>> mesh = pool.submit(() -> exchange(base + archiveMeshProduct
                                + "/mesh-configurations", HttpMethod.PUT,
                        authenticated(adminId, "admin3d-admin@example.invalid", Role.ADMIN, meshBody)));
                assertThat(awaitDatabaseWait("admin3d_lock_product", 1))
                        .as("mesh PUT is blocked on the same product row lock").isTrue();
                blocker.commit();
                HttpResponse<String> archived = archive.get(10, TimeUnit.SECONDS);
                HttpResponse<String> configured = mesh.get(10, TimeUnit.SECONDS);
                evidence("v17-barrier-archive-mesh", archived);
                evidence("v17-barrier-mesh-put", configured);
                assertThat(archived.statusCode()).isEqualTo(204);
                assertThat(configured.statusCode()).isIn(200, 409);
                assertThat(bootstrap.queryForObject("SELECT model_status FROM model_3d WHERE model_id=?",
                        String.class, archiveMeshProduct)).isEqualTo("INACTIVE");
                assertThat(bootstrap.queryForObject(
                        "SELECT count(*) FROM product_glb_structure_snapshots WHERE product_id=?",
                        Long.class, archiveMeshProduct)).isEqualTo(snapshotsBefore);
                assertThat(bootstrap.queryForObject(
                        "SELECT count(*) FROM product_mesh_configuration_revisions WHERE product_id=?",
                        Long.class, archiveMeshProduct)).isLessThanOrEqualTo(1L);
            }

            int lockTimeoutProduct = insertPublishableProduct("Barrier lock timeout draft");
            try (Connection blocker = writerSource.getConnection()) {
                blocker.setAutoCommit(false);
                try (var lock = blocker.prepareStatement("SELECT model_id FROM model_3d WHERE model_id=? FOR UPDATE")) {
                    lock.setInt(1, lockTimeoutProduct);
                    try (var rows = lock.executeQuery()) { assertThat(rows.next()).isTrue(); }
                }
                Future<HttpResponse<String>> publish = pool.submit(() -> exchange(base + lockTimeoutProduct
                                + "/publish", HttpMethod.POST,
                        authenticated(adminId, "admin3d-admin@example.invalid", Role.ADMIN, null)));
                assertThat(awaitDatabaseWait("admin3d_publish_designer_model", 1))
                        .as("publish reaches the PostgreSQL lock timeout boundary").isTrue();
                HttpResponse<String> timedOut = publish.get(5, TimeUnit.SECONDS);
                evidence("v17-publish-lock-timeout", timedOut);
                assertThat(timedOut.statusCode()).isEqualTo(409);
                assertThat(bootstrap.queryForObject("SELECT model_status FROM model_3d WHERE model_id=?",
                        String.class, lockTimeoutProduct)).isEqualTo("PENDING");
                blocker.rollback();
            }
        } finally {
            pool.shutdownNow();
        }
        verifyNoInteractions(storage);
    }

    private boolean awaitDatabaseWait(String queryFragment, int expectedCount) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        do {
            Integer count = bootstrap.queryForObject("""
                    SELECT count(*) FROM pg_catalog.pg_stat_activity
                    WHERE usename='app_runtime' AND wait_event_type='Lock' AND query ILIKE ?
                    """, Integer.class, "%" + queryFragment + "%");
            if (count != null && count >= expectedCount) return true;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        return false;
    }

    @Test
    void publishWaitsForMediaCleanupAndRejectsTheCommittedUnsafeAssetState() throws Exception {
        int product = insertPublishableProduct("Cleanup race draft");
        long mediaId = bootstrap.queryForObject("SELECT media_asset_id FROM model_3d WHERE model_id=?",
                Long.class, product);
        long snapshotsBefore = bootstrap.queryForObject(
                "SELECT count(*) FROM product_glb_structure_snapshots WHERE product_id=?", Long.class, product);
        DataSource writerSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
                "postgres", "admin3d_bootstrap_test_only");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection cleanupWriter = writerSource.getConnection()) {
            cleanupWriter.setAutoCommit(false);
            try (var update = cleanupWriter.prepareStatement(
                    "UPDATE public.media_asset SET state='CLEANUP_PENDING' WHERE id=?")) {
                update.setLong(1, mediaId);
                assertThat(update.executeUpdate()).isEqualTo(1);
            }

            String publishUrl = "http://127.0.0.1:" + port + "/api/admin/products/" + product + "/publish";
            Future<HttpResponse<String>> publish = executor.submit(() -> exchange(publishUrl, HttpMethod.POST,
                    authenticated(adminId, "admin3d-admin@example.invalid", Role.ADMIN, null)));
            assertThat(awaitPublishLockWait()).as("PostgreSQL reports the app_runtime publish blocked on the asset row")
                    .isTrue();

            cleanupWriter.commit();
            HttpResponse<String> response = publish.get(10, TimeUnit.SECONDS);
            evidence("restricted-publish-cleanup-race", response);
            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(bootstrap.queryForObject("SELECT model_status FROM model_3d WHERE model_id=?",
                    String.class, product)).isEqualTo("PENDING");
            assertThat(bootstrap.queryForObject("SELECT state FROM media_asset WHERE id=?",
                    String.class, mediaId)).isEqualTo("CLEANUP_PENDING");
            assertThat(bootstrap.queryForObject("SELECT count(*) FROM product_glb_structure_snapshots WHERE product_id=?",
                    Long.class, product)).isEqualTo(snapshotsBefore);
            assertThat(bootstrap.queryForObject("SELECT count(*) FROM product_mesh_configuration_revisions WHERE product_id=?",
                    Long.class, product)).isZero();
        } finally {
            executor.shutdownNow();
        }
        verifyNoInteractions(storage);
    }

    @Test
    void concurrentPublishEditArchiveAndMeshPutRemainSerializedAndPreserveHistory() throws Exception {
        int product = insertPublishableProduct("Concurrent command draft");
        long assetId = bootstrap.queryForObject("SELECT media_asset_id FROM model_3d WHERE model_id=?",
                Long.class, product);
        UUID structureId = bootstrap.queryForObject("""
                SELECT structure_snapshot_id FROM product_glb_structure_snapshots
                WHERE product_id=? ORDER BY snapshot_revision DESC LIMIT 1
                """, UUID.class, product);
        String base = "http://127.0.0.1:" + port + "/api/admin/products/" + product;
        String meshBody = JSON.writeValueAsString(Map.of(
                "mediaAssetId", assetId,
                "structureSnapshotId", structureId,
                "expectedRevision", 0,
                "materialId", materialId,
                "meshes", List.of(Map.of("nodeIndex", 0, "meshIndex", 0, "primitiveIndex", 0,
                        "customizable", true, "defaultColorId", colorId, "allowedColorIds", List.of(colorId)))));
        CountDownLatch ready = new CountDownLatch(4);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            Future<HttpResponse<String>> publish = pool.submit(() -> concurrentExchange(ready, start,
                    base + "/publish", HttpMethod.POST,
                    authenticated(adminId, "admin3d-admin@example.invalid", Role.ADMIN, null)));
            Future<HttpResponse<String>> edit = pool.submit(() -> concurrentExchange(ready, start,
                    base, HttpMethod.PATCH, authenticated(adminId, "admin3d-admin@example.invalid", Role.ADMIN,
                            "{\"description\":\"concurrent description\"}")));
            Future<HttpResponse<String>> archive = pool.submit(() -> concurrentExchange(ready, start,
                    base + "/archive", HttpMethod.POST,
                    authenticated(adminId, "admin3d-admin@example.invalid", Role.ADMIN, null)));
            Future<HttpResponse<String>> mesh = pool.submit(() -> concurrentExchange(ready, start,
                    base + "/mesh-configurations", HttpMethod.PUT,
                    authenticated(adminId, "admin3d-admin@example.invalid", Role.ADMIN, meshBody)));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            HttpResponse<String> publishResult = publish.get(15, TimeUnit.SECONDS);
            HttpResponse<String> editResult = edit.get(15, TimeUnit.SECONDS);
            HttpResponse<String> archiveResult = archive.get(15, TimeUnit.SECONDS);
            HttpResponse<String> meshResult = mesh.get(15, TimeUnit.SECONDS);
            System.out.println("HTTP_EVIDENCE concurrent-command statuses publish=" + publishResult.statusCode()
                    + " edit=" + editResult.statusCode() + " archive=" + archiveResult.statusCode()
                    + " mesh=" + meshResult.statusCode());
            assertThat(publishResult.statusCode()).isIn(204, 409);
            assertThat(editResult.statusCode()).isEqualTo(200);
            assertThat(archiveResult.statusCode()).isEqualTo(204);
            assertThat(meshResult.statusCode()).isIn(200, 409);
            assertThat(List.of(publishResult.statusCode(), editResult.statusCode(), archiveResult.statusCode(),
                    meshResult.statusCode())).allMatch(status -> status < 500);
        } finally {
            start.countDown();
            pool.shutdownNow();
        }

        Map<String, Object> finalProduct = bootstrap.queryForMap(
                "SELECT name,description,category,model_status FROM model_3d WHERE model_id=?", product);
        assertThat(finalProduct).containsEntry("name", "Concurrent command draft")
                .containsEntry("description", "concurrent description")
                .containsEntry("category", "acceptance")
                .containsEntry("model_status", "INACTIVE");
        assertThat(bootstrap.queryForObject("SELECT count(*) FROM product_glb_structure_snapshots WHERE product_id=?",
                Long.class, product)).isEqualTo(1L);
        long configurationCount = bootstrap.queryForObject(
                "SELECT count(*) FROM product_mesh_configuration_revisions WHERE product_id=?", Long.class, product);
        assertThat(configurationCount).isIn(0L, 1L);
        verifyNoInteractions(storage);
    }

    private HttpResponse<String> concurrentExchange(CountDownLatch ready, CountDownLatch start, String url,
                                                     HttpMethod method, HttpEntity<String> request) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent test did not start");
        return exchange(url, method, request);
    }

    private boolean awaitPublishLockWait() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        do {
            Boolean waiting = bootstrap.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1 FROM pg_catalog.pg_stat_activity
                        WHERE usename='app_runtime' AND wait_event_type='Lock'
                          AND query ILIKE '%admin3d_publish_designer_model%'
                    )
                    """, Boolean.class);
            if (Boolean.TRUE.equals(waiting)) return true;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        return false;
    }

    private static String sqlState(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLException sqlException) return sqlException.getSQLState();
            current = current.getCause();
        }
        return null;
    }

    private int insertPublishableProduct(String name) throws Exception {
        UUID operationId = UUID.randomUUID();
        long modelAssetId = insertPublishableAsset(operationId, "MODEL_3D", "raw", "glb");
        String modelPublicId = "product-media/models/" + operationId + "/" + modelAssetId + ".glb";
        String modelUrl = "https://res.cloudinary.com/test-cloud/raw/upload/v1/" + modelPublicId;
        bootstrap.update("UPDATE media_asset SET public_id=?,secure_url=? WHERE id=?", modelPublicId, modelUrl, modelAssetId);
        int id = bootstrap.queryForObject("""
                INSERT INTO model_3d(source_type,user_id,name,file_path_3d,category,model_status,media_asset_id)
                VALUES ('DESIGNER_MODEL',?,?,?,'acceptance','PENDING',?) RETURNING model_id
                """, Integer.class, adminId, name, modelPublicId, modelAssetId);

        long imageAssetId = insertPublishableAsset(operationId, "IMAGE", "image", "png");
        String imagePublicId = "product-media/images/" + operationId + "/" + imageAssetId;
        String imageUrl = "https://res.cloudinary.com/test-cloud/image/upload/v1/" + imagePublicId + ".png";
        bootstrap.update("UPDATE media_asset SET public_id=?,secure_url=? WHERE id=?", imagePublicId, imageUrl, imageAssetId);
        bootstrap.update("INSERT INTO model_images(model_id,image_path,media_asset_id,is_primary) VALUES (?,?,?,TRUE)",
                id, imagePublicId, imageAssetId);
        bootstrap.update("""
                INSERT INTO product_glb_structure_snapshots
                    (structure_snapshot_id,product_id,media_asset_id,snapshot_revision,parser_version,structure_json,created_by)
                VALUES (?,?,?,1,'glb-structure-1',?::jsonb,?)
                """, UUID.randomUUID(), id, modelAssetId, JSON.writeValueAsString(validStructure()), adminId);
        return id;
    }

    private long insertPublishableAsset(UUID operationId, String kind, String resourceType, String format) {
        return bootstrap.queryForObject("""
                INSERT INTO media_asset(operation_id,media_kind,provider,cloud_name,provider_asset_id,public_id,
                    resource_type,delivery_type,format,byte_count,provider_version,secure_url,state)
                VALUES (?,?,'CLOUDINARY','test-cloud',?,'pending-test-public-id',?,'upload',?,128,1,
                    'https://res.cloudinary.com/test-cloud/pending','ATTACHED') RETURNING id
                """, Long.class, operationId, kind, "provider-asset-" + UUID.randomUUID(), resourceType, format);
    }

    @Test
    void snapshotPostCommitsSnapshotAndImmediatelyServesBothMeshReads() throws Exception {
        configureFakeStorage();
        byte[] glb = validGlb();
        byte[] image = validPng();
        HttpResponse<String> created = postMultipart("/api/admin/products", adminId,
                "admin3d-admin@example.invalid", Role.ADMIN, "HTTP snapshot product", image, glb);
        evidence("snapshot-product-post", created);
        assertThat(created.statusCode()).isEqualTo(201);
        var response = JSON.readTree(created.body());
        int createdProductId = response.path("id").asInt();
        assertThat(createdProductId).isPositive();
        assertThat(response.path("sourceType").asText()).isEqualTo("DESIGNER_MODEL");

        long createdMediaAssetId = bootstrap.queryForObject(
                "SELECT media_asset_id FROM model_3d WHERE model_id=?", Long.class, createdProductId);
        UUID operationId = bootstrap.queryForObject("SELECT operation_id FROM media_asset WHERE id=?",
                UUID.class, createdMediaAssetId);
        assertThat(bootstrap.queryForObject("SELECT count(*) FROM media_asset WHERE operation_id=? "
                + "AND media_kind='MODEL_3D' AND state='ATTACHED'", Integer.class, operationId)).isEqualTo(1);
        assertThat(bootstrap.queryForObject("SELECT count(*) FROM media_asset WHERE operation_id=? "
                + "AND media_kind='IMAGE' AND state='ATTACHED'", Integer.class, operationId)).isEqualTo(1);
        var snapshot = bootstrap.queryForMap("""
                SELECT structure_snapshot_id, snapshot_revision, parser_version, structure_json::text
                FROM product_glb_structure_snapshots WHERE product_id=? AND media_asset_id=?
                """, createdProductId, createdMediaAssetId);
        UUID createdSnapshotId = (UUID) snapshot.get("structure_snapshot_id");
        assertThat(snapshot.get("snapshot_revision")).isEqualTo(1L);
        assertThat(snapshot.get("parser_version")).isEqualTo(com.exe.morphia3d.entity.ProductGlbStructureSnapshot.CURRENT_PARSER_VERSION);
        assertThat(JSON.readTree((String) snapshot.get("structure_json")).path("primitiveOccurrences")).hasSize(1);
        assertThat(bootstrap.queryForObject("SELECT count(*) FROM product_mesh_configuration_revisions WHERE product_id=?",
                Integer.class, createdProductId)).isZero();

        assertThat(storedUploads).hasSize(2);
        StoredUpload uploadedModel = storedUploads.stream().filter(upload -> upload.kind() == MediaKind.MODEL_3D)
                .findFirst().orElseThrow();
        assertThat(uploadedModel.sha256()).isEqualTo(sha256(glb));
        assertThat(uploadedModel.bytes()).containsExactly(glb);

        String base = "http://127.0.0.1:" + port + "/api/admin/products/" + createdProductId;
        HttpEntity<String> admin = authenticated(adminId, "admin3d-admin@example.invalid", Role.ADMIN, null);
        HttpResponse<String> structure = exchange(base + "/mesh-structure", HttpMethod.GET, admin);
        evidence("created-product-structure-get", structure);
        assertThat(structure.statusCode()).isEqualTo(200);
        assertThat(structure.body()).contains(createdSnapshotId.toString(), "\"mediaAssetId\":" + createdMediaAssetId,
                "\"parserVersion\":\"" + com.exe.morphia3d.entity.ProductGlbStructureSnapshot.CURRENT_PARSER_VERSION
                        + "\"", "primitiveOccurrences");

        HttpResponse<String> configuration = exchange(base + "/mesh-configurations", HttpMethod.GET, admin);
        evidence("created-product-configuration-get", configuration);
        assertThat(configuration.statusCode()).isEqualTo(200);
        assertThat(configuration.body()).contains("\"configured\":false", "\"revision\":0",
                "\"materialId\":null", "\"meshes\":[]", createdSnapshotId.toString())
                .contains("\"mediaAssetId\":" + createdMediaAssetId);
        System.out.printf("HTTP_SNAPSHOT_POST_GET productId=%d mediaAssetId=%d structureSnapshotId=%s "
                        + "revision=1 parserVersion=%s occurrenceCount=1 configRevision=0 sourceSha256=%s storageSha256=%s%n",
                createdProductId, createdMediaAssetId, createdSnapshotId, snapshot.get("parser_version"),
                sha256(glb), uploadedModel.sha256());
    }

    @Test
    void malformedGlbFailsBeforeStorageAndProviderFailureLeavesNoProductOrSnapshot() throws Exception {
        configureFakeStorage();
        int productsBefore = bootstrap.queryForObject("SELECT count(*) FROM model_3d", Integer.class);
        int snapshotsBefore = bootstrap.queryForObject("SELECT count(*) FROM product_glb_structure_snapshots", Integer.class);
        HttpResponse<String> malformed = postMultipart("/api/admin/products", adminId,
                "admin3d-admin@example.invalid", Role.ADMIN, "Malformed snapshot product", validPng(), headerOnlyGlb());
        evidence("snapshot-malformed-glb", malformed);
        assertThat(malformed.statusCode()).isEqualTo(415);
        verifyNoInteractions(storage);
        assertThat(bootstrap.queryForObject("SELECT count(*) FROM model_3d", Integer.class)).isEqualTo(productsBefore);
        assertThat(bootstrap.queryForObject("SELECT count(*) FROM product_glb_structure_snapshots", Integer.class))
                .isEqualTo(snapshotsBefore);

        reset(storage);
        doThrow(new MediaStorageException(MediaStorageException.Reason.PROVIDER_UNAVAILABLE))
                .when(storage).upload(any(MediaUploadIntent.class), any(ValidatedMedia.class));
        HttpResponse<String> uploadFailure = postMultipart("/api/admin/products", adminId,
                "admin3d-admin@example.invalid", Role.ADMIN, "Storage failure product", validPng(), validGlb());
        evidence("snapshot-storage-failure", uploadFailure);
        assertThat(uploadFailure.statusCode()).isEqualTo(503);
        assertThat(uploadFailure.body()).doesNotContain("jdbc:", "private", "stackTrace");
        assertThat(bootstrap.queryForObject("SELECT count(*) FROM model_3d", Integer.class)).isEqualTo(productsBefore);
        assertThat(bootstrap.queryForObject("SELECT count(*) FROM product_glb_structure_snapshots", Integer.class))
                .isEqualTo(snapshotsBefore);
    }

    private void configureFakeStorage() throws Exception {
        storedUploads.clear();
        reset(storage);
        doAnswer(invocation -> {
            MediaUploadIntent intent = invocation.getArgument(0);
            ValidatedMedia media = invocation.getArgument(1);
            byte[] bytes;
            try (var stream = media.file().getInputStream()) { bytes = stream.readAllBytes(); }
            storedUploads.add(new StoredUpload(intent.mediaKind(), bytes, sha256(bytes)));
            String format = media.mediaKind() == MediaKind.MODEL_3D ? "glb" : "png";
            return new MediaUploadResult(MediaProvider.CLOUDINARY, "test-cloud",
                    "fake-asset-" + intent.mediaAssetId(), intent.publicId(), intent.mediaKind(),
                    intent.resourceType(), intent.deliveryType(), format, media.byteCount(), 1L,
                    "https://res.cloudinary.com/test-cloud/" + intent.resourceType() + "/upload/v1/"
                            + intent.publicId() + (media.mediaKind() == MediaKind.IMAGE ? ".png" : ""));
        }).when(storage).upload(any(MediaUploadIntent.class), any(ValidatedMedia.class));
    }

    private int concurrentPut(String base, String body, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent HTTP start timed out");
        return exchange(base + "/mesh-configurations", HttpMethod.PUT,
                authenticated(adminId, "admin3d-admin@example.invalid", Role.ADMIN, body)).statusCode();
    }

    private static void evidence(String caseId, HttpResponse<String> response) {
        System.out.println("HTTP_EVIDENCE " + caseId + " status=" + response.statusCode()
                + " content-type=" + response.headers().firstValue("Content-Type").orElse("<absent>")
                + " body=" + response.body());
    }

    private HttpEntity<String> authenticated(int id, String email, Role role, String body) {
        User user = User.builder().id(id).name("test identity").email(email).password("unused-test-password").role(role).build();
        String token = jwt.generateToken(new UserPrincipal(user));
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        if (body != null) headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private static HttpResponse<String> exchange(String uri, HttpMethod method, HttpEntity<String> entity)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(uri));
        entity.getHeaders().forEach((name, values) -> values.forEach(value -> request.header(name, value)));
        HttpRequest.BodyPublisher publisher = entity.getBody() == null
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(entity.getBody());
        return HTTP.send(request.method(method.name(), publisher).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postMultipart(String path, int id, String email, Role role,
                                               String productName, byte[] image, byte[] glb) throws Exception {
        String boundary = "----Admin3dBoundary" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writePart(body, boundary, "data", "data.json", "application/json",
                ("{\"name\":\"" + productName + "\",\"category\":\"acceptance\"}")
                        .getBytes(StandardCharsets.UTF_8));
        writePart(body, boundary, "images", "preview.png", "image/png", image);
        writePart(body, boundary, "model", "model.glb", "model/gltf-binary", glb);
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        User user = User.builder().id(id).name("test identity").email(email)
                .password("unused-test-password").role(role).build();
        String token = jwt.generateToken(new UserPrincipal(user));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void writePart(ByteArrayOutputStream output, String boundary, String name,
                                  String filename, String contentType, byte[] bytes) throws Exception {
        output.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name
                + "\"; filename=\"" + filename + "\"\r\nContent-Type: " + contentType
                + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        output.write(bytes);
        output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] validGlb() {
        byte[] json = ("{\"asset\":{\"version\":\"2.0\"},\"scene\":0,"
                + "\"scenes\":[{\"nodes\":[0]}],\"nodes\":[{\"name\":\"Lamp body\",\"mesh\":0}],"
                + "\"meshes\":[{\"name\":\"Body mesh\",\"primitives\":[{\"attributes\":{\"POSITION\":0}}]}],"
                + "\"accessors\":[{\"componentType\":5126,\"count\":1,\"type\":\"VEC3\",\"min\":[0,0,0],\"max\":[0,0,0]}],"
                + "\"buffers\":[{\"byteLength\":4}]}" ).getBytes(StandardCharsets.UTF_8);
        int paddedJson = (json.length + 3) & ~3;
        int totalLength = 12 + 8 + paddedJson + 8 + 4;
        ByteBuffer result = ByteBuffer.allocate(totalLength).order(ByteOrder.LITTLE_ENDIAN);
        result.put(new byte[]{'g', 'l', 'T', 'F'}).putInt(2).putInt(totalLength);
        result.putInt(paddedJson).putInt(0x4e4f534a).put(json);
        while (result.position() < 12 + 8 + paddedJson) result.put((byte) ' ');
        result.putInt(4).putInt(0x004e4942).put(new byte[]{0, 0, 0, 0});
        return result.array();
    }

    /** Legacy upload validation accepts the envelope, while the structural parser must reject missing chunks. */
    private static byte[] headerOnlyGlb() {
        return ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                .put(new byte[]{'g', 'l', 'T', 'F'}).putInt(2).putInt(12).array();
    }

    private static byte[] validPng() throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private record StoredUpload(MediaKind kind, byte[] bytes, String sha256) { }

    private static int insertUser(String email, String role) {
        return bootstrap.queryForObject("""
                INSERT INTO users(name,email,password,role,status) VALUES ('Admin 3D HTTP test',?,?,?,'ACTIVE') RETURNING user_id
                """, Integer.class, email, "test-only-password", role);
    }

    private static GlbStructure validStructure() {
        return new GlbStructure(-1, List.of(),
                List.of(new GlbStructure.Node(0, "Node", 0, List.of(), List.of())),
                List.of(new GlbStructure.Mesh(0, "Mesh", List.of(
                        new GlbStructure.Primitive(0, null, List.of("POSITION"), List.of())))),
                List.of(), List.of(), List.of(),
                List.of(new GlbStructure.PrimitiveOccurrence(0, 0, 0, "Node", null)));
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
        bootstrap.execute("GRANT CONNECT ON DATABASE admin3d_real_http_test TO admin3d_migrator, app_runtime");
        bootstrap.execute("GRANT USAGE, CREATE ON SCHEMA public TO admin3d_owner");
        bootstrap.execute("GRANT USAGE, CREATE ON SCHEMA public TO admin3d_migrator WITH GRANT OPTION");
        bootstrap.execute("GRANT USAGE ON SCHEMA public TO app_runtime");
    }

    private static void grantRuntimeReadAndHistoryInsert() {
        bootstrap.execute("GRANT SELECT ON TABLE public.users, public.staff_profiles, public.colors, public.model_3d, public.model_parts, public.model_images, public.media_asset, public.materials, public.material_colors TO app_runtime");
        bootstrap.execute("GRANT INSERT ON TABLE public.model_3d, public.model_images TO app_runtime");
        bootstrap.execute("GRANT SELECT, INSERT, UPDATE ON TABLE public.media_asset TO app_runtime");
        bootstrap.execute("GRANT SELECT, INSERT ON TABLE public.product_glb_structure_snapshots, public.product_mesh_configuration_revisions TO app_runtime");
        bootstrap.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO app_runtime");
    }
}
