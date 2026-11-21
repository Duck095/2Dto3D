package com.exe.morphia3d.service;

import com.exe.morphia3d.Morphia3dApplication;
import com.exe.morphia3d.dto.request.DesignerModelRequest;
import com.exe.morphia3d.dto.response.ModelResponse;
import com.exe.morphia3d.entity.ProductGlbStructureSnapshot;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.MediaAssetState;
import com.exe.morphia3d.enums.MediaKind;
import com.exe.morphia3d.enums.MediaProvider;
import com.exe.morphia3d.enums.Role;
import com.exe.morphia3d.security.UserPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.springframework.web.multipart.MultipartFile;

import javax.sql.DataSource;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

/** Full internal GLB upload path with real lifecycle/JPA/PostgreSQL and a non-network storage fake. */
@SpringBootTest(classes = Morphia3dApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.jpa.hibernate.ddl-auto=none",
                "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
                "spring.flyway.enabled=false",
                "media.lifecycle.reconciliation-enabled=false",
                "cloudinary.cloud-name=test-cloud"
        })
@ActiveProfiles("test")
class DesignerModelSnapshotPostgresAcceptanceTest {
    private static final String IMAGE = "postgres@sha256:721873c34ceb9f8d8fc265984940dc982404c105f19ad51be9fdc5970a6080ea";
    private static final String MIGRATOR_PASSWORD = "snapshot_pg_migrator_test_only";
    private static final String RUNTIME_PASSWORD = "snapshot_pg_runtime_test_only";
    private static final Path SPOOL_DIRECTORY = testOwnedSpoolDirectory();
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(IMAGE)
            .withDatabaseName("admin3d_snapshot_upload_test")
            .withUsername("postgres")
            .withPassword("snapshot_pg_bootstrap_test_only");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static JdbcTemplate bootstrap;
    private static int designerId;

    static {
        try {
            verifyTestOwnedSpoolDirectory();
            prepareDisposableDatabase();
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @Autowired private DesignerModelUploadService uploadService;
    @MockitoBean private MediaStorageService storage;
    private final List<UploadCall> uploadCalls = new CopyOnWriteArrayList<>();

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "app_runtime");
        registry.add("spring.datasource.password", () -> RUNTIME_PASSWORD);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("media.upload.snapshot-temp-directory", SPOOL_DIRECTORY::toString);
    }

    @BeforeEach
    void configureFakeStorageAndSpool() throws IOException {
        verifyTestOwnedSpoolDirectory();
        Files.createDirectories(SPOOL_DIRECTORY);
        try (var entries = Files.list(SPOOL_DIRECTORY)) {
            assertThat(entries.toList()).isEmpty();
        }
        uploadCalls.clear();
        reset(storage);
        doAnswer(invocation -> {
            MediaUploadIntent intent = invocation.getArgument(0);
            ValidatedMedia media = invocation.getArgument(1);
            byte[] received;
            try (var input = media.file().getInputStream()) {
                received = input.readAllBytes();
            }
            String digest = sha256(received);
            String spoolDigest = media.file() instanceof SnapshotGlbSpool.FileBackedMultipartFile spool
                    ? spool.sha256Hex() : "<not-a-trusted-spool>";
            uploadCalls.add(new UploadCall(intent, received, digest, spoolDigest));
            String format = media.mediaKind() == MediaKind.MODEL_3D ? "glb" : "png";
            String suffix = media.mediaKind() == MediaKind.IMAGE ? "." + format : "";
            return new MediaUploadResult(MediaProvider.CLOUDINARY, "test-cloud",
                    "fake-asset-" + intent.mediaAssetId() + "-" + UUID.randomUUID(), intent.publicId(),
                    intent.mediaKind(), intent.resourceType(), intent.deliveryType(), format,
                    media.byteCount(), 1L, "https://res.cloudinary.com/test-cloud/" + intent.resourceType()
                    + "/upload/v1/" + intent.publicId() + suffix);
        }).when(storage).upload(any(), any());
    }

    @AfterEach
    void verifyNoProviderDeletion() throws IOException {
        verify(storage, never()).delete(any());
        try (var entries = Files.list(SPOOL_DIRECTORY)) {
            assertThat(entries.toList()).isEmpty();
        }
        bootstrap.execute("DROP TRIGGER IF EXISTS trg_test_fail_snapshot_insert ON public.product_glb_structure_snapshots");
        bootstrap.execute("DROP FUNCTION IF EXISTS public.fail_snapshot_insert_for_acceptance()");
    }

    @AfterAll
    static void stopDisposablePostgres() {
        if (POSTGRES.isRunning()) POSTGRES.stop();
    }

    private static Path testOwnedSpoolDirectory() {
        try {
            return Path.of(System.getProperty("java.io.tmpdir")).toRealPath()
                    .resolve(Path.of("2dto3d-test-spool", "designer-model-snapshot",
                            UUID.randomUUID().toString(), "snapshot-spool"))
                    .toAbsolutePath().normalize();
        } catch (IOException failure) {
            throw new IllegalStateException("Could not resolve the OS temp directory for the acceptance spool", failure);
        }
    }

    private static void verifyTestOwnedSpoolDirectory() throws IOException {
        Path tempRoot = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
        Path spool = SPOOL_DIRECTORY.toAbsolutePath().normalize();
        if (!spool.equals(SPOOL_DIRECTORY) || !spool.startsWith(tempRoot) || spool.equals(tempRoot)) {
            throw new IllegalStateException("Acceptance spool must be a normalized child of the OS temp directory");
        }

        Path current = tempRoot;
        for (Path segment : tempRoot.relativize(spool)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalStateException("Acceptance spool path must not traverse a symbolic link");
            }
        }

        Path repositoryRoot = findRepositoryRoot();
        Path workspaceParent = repositoryRoot.getParent();
        if (workspaceParent != null && spool.startsWith(workspaceParent.toRealPath())) {
            throw new IllegalStateException("Acceptance spool must be outside the repository workspace");
        }
    }

    private static Path findRepositoryRoot() throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toRealPath();
        while (current != null) {
            if (Files.exists(current.resolve(".git"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate the Git root for the acceptance test");
    }

    @Test
    void atomicSuccessUploadsTrustedGlbAndCommitsProductImageAttachmentAndSnapshot() throws Exception {
        byte[] sourceGlb = validGlb();
        byte[] sourceImage = png();
        UploadOutcome outcome = upload(sourceGlb, sourceImage, "snapshot-success-" + UUID.randomUUID());

        assertThat(outcome.response().getId()).isPositive();
        assertThat(uploadCalls).hasSize(2);
        UploadCall modelCall = uploadCalls.get(0);
        UploadCall imageCall = uploadCalls.get(1);
        assertThat(modelCall.intent().mediaKind()).isEqualTo(MediaKind.MODEL_3D);
        assertThat(imageCall.intent().mediaKind()).isEqualTo(MediaKind.IMAGE);
        assertThat(modelCall.intent().operationId()).isEqualTo(imageCall.intent().operationId());
        assertThat(modelCall.bytes()).containsExactly(sourceGlb);
        assertThat(modelCall.sha256()).isEqualTo(sha256(sourceGlb));
        assertThat(modelCall.spoolSha256()).isEqualTo(modelCall.sha256());
        assertThat(imageCall.bytes()).containsExactly(sourceImage);

        Integer productCount = bootstrap.queryForObject(
                "SELECT count(*) FROM model_3d WHERE model_id=?", Integer.class, outcome.response().getId());
        assertThat(productCount).isEqualTo(1);
        Long modelAssetId = bootstrap.queryForObject(
                "SELECT media_asset_id FROM model_3d WHERE model_id=?", Long.class, outcome.response().getId());
        assertThat(modelAssetId).isEqualTo(modelCall.intent().mediaAssetId());
        assertThat(bootstrap.queryForObject("""
                SELECT count(*) FROM model_images WHERE model_id=? AND media_asset_id=? AND is_primary
                """, Integer.class, outcome.response().getId(), imageCall.intent().mediaAssetId())).isEqualTo(1);
        assertThat(bootstrap.queryForObject("""
                SELECT count(*) FROM media_asset WHERE operation_id=? AND state='ATTACHED'
                """, Integer.class, modelCall.intent().operationId())).isEqualTo(2);

        MapSnapshot snapshot = bootstrap.queryForObject("""
                SELECT product_id,media_asset_id,snapshot_revision,parser_version,structure_json::text,created_at
                FROM product_glb_structure_snapshots WHERE product_id=? AND media_asset_id=?
                """, (rs, row) -> new MapSnapshot(rs.getInt(1), rs.getLong(2), rs.getLong(3),
                rs.getString(4), readJson(rs.getString(5)), rs.getTimestamp(6)),
                outcome.response().getId(), modelAssetId);
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.productId()).isEqualTo(outcome.response().getId());
        assertThat(snapshot.mediaAssetId()).isEqualTo(modelAssetId);
        assertThat(snapshot.revision()).isEqualTo(1L);
        assertThat(snapshot.parserVersion()).isEqualTo(ProductGlbStructureSnapshot.CURRENT_PARSER_VERSION);
        assertThat(snapshot.structure().get("meshes")).hasSize(1);
        assertThat(snapshot.structure().get("primitiveOccurrences")).hasSize(1);
        assertThat(bootstrap.queryForObject("SELECT count(*) FROM product_glb_structure_snapshots WHERE product_id=?",
                Integer.class, outcome.response().getId())).isEqualTo(1);
        verify(storage, org.mockito.Mockito.times(2)).upload(any(), any());
        verify(storage, never()).delete(any());
        System.out.printf("GLB_ATOMIC_SUCCESS productId=%d mediaAssetId=%d bytes=%d sourceSha256=%s storageSha256=%s spoolSha256=%s imageBytes=%d mediaStates=ATTACHED snapshotRevision=%d parserVersion=%s providerDeleteCalls=0%n",
                outcome.response().getId(), modelAssetId, sourceGlb.length, sha256(sourceGlb), modelCall.sha256(),
                modelCall.spoolSha256(), sourceImage.length, snapshot.revision(), snapshot.parserVersion());
    }

    @Test
    void snapshotInsertFailureRollsBackReferencesAndCompensatesOnlyDatabaseState() throws Exception {
        int productsBefore = bootstrap.queryForObject("SELECT count(*) FROM model_3d", Integer.class);
        int imagesBefore = bootstrap.queryForObject("SELECT count(*) FROM model_images", Integer.class);
        int snapshotsBefore = bootstrap.queryForObject(
                "SELECT count(*) FROM product_glb_structure_snapshots", Integer.class);
        bootstrap.execute("""
                CREATE FUNCTION public.fail_snapshot_insert_for_acceptance() RETURNS trigger
                LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'forced snapshot persistence failure'
                    USING ERRCODE='23514'; END $$
                """);
        bootstrap.execute("""
                CREATE TRIGGER trg_test_fail_snapshot_insert
                BEFORE INSERT ON public.product_glb_structure_snapshots
                FOR EACH ROW EXECUTE FUNCTION public.fail_snapshot_insert_for_acceptance()
                """);

        byte[] sourceGlb = validGlb();
        byte[] sourceImage = png();
        assertThatThrownBy(() -> upload(sourceGlb, sourceImage, "snapshot-rollback-" + UUID.randomUUID()))
                .isInstanceOf(MediaPersistenceException.class)
                .satisfies(failure -> assertThat(((MediaPersistenceException) failure).isSafeToCompensate()).isTrue());

        assertThat(uploadCalls).hasSize(2);
        UploadCall modelCall = uploadCalls.get(0);
        assertThat(modelCall.bytes()).containsExactly(sourceGlb);
        assertThat(modelCall.sha256()).isEqualTo(sha256(sourceGlb));
        assertThat(modelCall.spoolSha256()).isEqualTo(modelCall.sha256());
        assertThat(bootstrap.queryForObject("SELECT count(*) FROM model_3d", Integer.class)).isEqualTo(productsBefore);
        assertThat(bootstrap.queryForObject("SELECT count(*) FROM model_images", Integer.class)).isEqualTo(imagesBefore);
        assertThat(bootstrap.queryForObject("SELECT count(*) FROM product_glb_structure_snapshots", Integer.class))
                .isEqualTo(snapshotsBefore);
        assertThat(bootstrap.queryForObject("""
                SELECT count(*) FROM media_asset WHERE operation_id=? AND state='CLEANUP_PENDING'
                    AND lease_owner IS NULL AND lease_token IS NULL
                """, Integer.class, modelCall.intent().operationId())).isEqualTo(2);
        assertThat(bootstrap.queryForObject("""
                SELECT count(*) FROM media_asset asset LEFT JOIN model_3d product
                    ON product.media_asset_id=asset.id LEFT JOIN model_images image ON image.media_asset_id=asset.id
                WHERE asset.operation_id=? AND (product.model_id IS NOT NULL OR image.image_id IS NOT NULL)
                """, Integer.class, modelCall.intent().operationId())).isZero();
        verify(storage, org.mockito.Mockito.times(2)).upload(any(), any());
        verify(storage, never()).delete(any());
        System.out.printf("GLB_ATOMIC_ROLLBACK operationId=%s bytes=%d sourceSha256=%s storageSha256=%s spoolSha256=%s mediaStates=CLEANUP_PENDING count=2 productDelta=0 imageAssociationDelta=0 snapshotDelta=0 providerDeleteCalls=0%n",
                modelCall.intent().operationId(), sourceGlb.length, sha256(sourceGlb), modelCall.sha256(),
                modelCall.spoolSha256());
    }

    private UploadOutcome upload(byte[] glb, byte[] image, String name) {
        User designer = User.builder().id(designerId).name("Acceptance designer")
                .email("snapshot-designer@example.invalid").password("test-only-unused")
                .role(Role.DESIGNER).build();
        UserPrincipal principal = new UserPrincipal(designer);
        DesignerModelRequest metadata = new DesignerModelRequest();
        metadata.setName(name);
        metadata.setCategory("acceptance");
        List<MultipartFile> images = List.of(new MockMultipartFile("images", "front.png", "image/png", image));
        MultipartFile model = new MockMultipartFile("model", "model.glb", "model/gltf-binary", glb);
        ModelResponse response = uploadService.createWithSnapshot(principal, metadata, images, model);
        return new UploadOutcome(response);
    }

    private static void prepareDisposableDatabase() throws Exception {
        POSTGRES.start();
        DataSource root = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "postgres", "snapshot_pg_bootstrap_test_only");
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
        grantRuntimeUploadPrivileges();
        designerId = bootstrap.queryForObject("""
                INSERT INTO users(name,email,password,role,status)
                VALUES ('Disposable snapshot QA designer','snapshot-designer@example.invalid','unused','DESIGNER','ACTIVE')
                RETURNING user_id
                """, Integer.class);
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
        bootstrap.execute("GRANT CONNECT ON DATABASE admin3d_snapshot_upload_test TO admin3d_migrator, app_runtime");
        bootstrap.execute("GRANT USAGE, CREATE ON SCHEMA public TO admin3d_owner");
        bootstrap.execute("GRANT USAGE, CREATE ON SCHEMA public TO admin3d_migrator WITH GRANT OPTION");
        bootstrap.execute("GRANT USAGE ON SCHEMA public TO app_runtime");
    }

    private static void grantRuntimeUploadPrivileges() {
        bootstrap.execute("GRANT SELECT ON TABLE public.users, public.staff_profiles, public.colors, public.model_3d, public.model_images, public.media_asset, public.materials, public.material_colors TO app_runtime");
        bootstrap.execute("GRANT SELECT ON TABLE public.product_mesh_configuration_revisions TO app_runtime");
        bootstrap.execute("GRANT INSERT ON TABLE public.model_3d, public.model_images TO app_runtime");
        bootstrap.execute("GRANT SELECT, INSERT, UPDATE ON TABLE public.media_asset TO app_runtime");
        bootstrap.execute("GRANT SELECT, INSERT ON TABLE public.product_glb_structure_snapshots TO app_runtime");
        bootstrap.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO app_runtime");
    }

    private static byte[] png() throws IOException {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private static byte[] validGlb() {
        byte[] json = ("{\"asset\":{\"version\":\"2.0\"},\"scene\":0,"
                + "\"scenes\":[{\"nodes\":[0]}],\"nodes\":[{\"name\":\"Lamp body\",\"mesh\":0}],"
                + "\"meshes\":[{\"name\":\"Body mesh\",\"primitives\":[{\"attributes\":{\"POSITION\":0}}]}],"
                + "\"accessors\":[{\"componentType\":5126,\"count\":1,\"type\":\"VEC3\",\"min\":[0,0,0],\"max\":[0,0,0]}],"
                + "\"buffers\":[{\"byteLength\":4}]}").getBytes(StandardCharsets.UTF_8);
        int paddedJson = (json.length + 3) & ~3;
        int totalLength = 12 + 8 + paddedJson + 8 + 4;
        ByteBuffer result = ByteBuffer.allocate(totalLength).order(ByteOrder.LITTLE_ENDIAN);
        result.put(new byte[]{'g', 'l', 'T', 'F'}).putInt(2).putInt(totalLength);
        result.putInt(paddedJson).putInt(0x4e4f534a).put(json);
        while (result.position() < 12 + 8 + paddedJson) result.put((byte) ' ');
        result.putInt(4).putInt(0x004e4942).put(new byte[]{0, 0, 0, 0});
        return result.array();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static JsonNode readJson(String value) {
        try {
            return JSON.readTree(value);
        } catch (IOException exception) {
            throw new IllegalStateException("Stored structure snapshot is not valid JSON", exception);
        }
    }

    private record UploadCall(MediaUploadIntent intent, byte[] bytes, String sha256, String spoolSha256) { }
    private record UploadOutcome(ModelResponse response) { }
    private record MapSnapshot(int productId, long mediaAssetId, long revision, String parserVersion,
                               JsonNode structure, Timestamp createdAt) { }
}
