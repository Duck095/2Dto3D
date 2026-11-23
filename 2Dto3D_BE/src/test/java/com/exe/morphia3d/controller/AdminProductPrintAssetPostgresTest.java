package com.exe.morphia3d.controller;

import com.exe.morphia3d.Morphia3dApplication;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.MediaKind;
import com.exe.morphia3d.enums.MediaProvider;
import com.exe.morphia3d.enums.Role;
import com.exe.morphia3d.enums.UserStatus;
import com.exe.morphia3d.service.DeleteResult;
import com.exe.morphia3d.service.MediaAssetIdentity;
import com.exe.morphia3d.service.MediaStorageException;
import com.exe.morphia3d.service.MediaStorageService;
import com.exe.morphia3d.service.MediaUploadIntent;
import com.exe.morphia3d.service.MediaUploadResult;
import com.exe.morphia3d.service.MediaAssetLifecycleService;
import com.exe.morphia3d.service.ValidatedMedia;
import com.exe.morphia3d.security.UserPrincipal;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.mock.web.MockMultipartFile;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
@SpringBootTest(classes = Morphia3dApplication.class)
@org.springframework.context.annotation.Import(AdminProductPrintAssetPostgresTest.StorageConfiguration.class)
class AdminProductPrintAssetPostgresTest {

    private static final String RUNTIME_PASSWORD = "print-asset-runtime-test-only";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("print_asset_api_test")
            .withUsername("print_asset_bootstrap")
            .withPassword("print_asset_bootstrap_only");

    private static JdbcTemplate bootstrap;

    @Autowired private WebApplicationContext context;
    @Autowired private RecordingStorage storage;
    @Autowired private MediaAssetLifecycleService lifecycle;
    private MockMvc mvc;
    private final List<Integer> users = new ArrayList<>();
    private final List<Integer> products = new ArrayList<>();
    private final List<Long> mediaAssets = new ArrayList<>();

    @DynamicPropertySource
    static void configureDisposablePostgres(DynamicPropertyRegistry registry) {
        POSTGRES.start();
        bootstrap = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        provisionRoles();
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").target(MigrationVersion.fromVersion("14")).load().migrate();
        bootstrap.execute("GRANT REFERENCES ON TABLE public.model_3d, public.media_asset, public.materials, public.users TO admin3d_owner");
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "app_runtime");
        registry.add("spring.datasource.password", () -> RUNTIME_PASSWORD);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("cloudinary.cloud-name", () -> "test-cloud");
    }

    @BeforeEach
    void setUpMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        storage.reset();
    }

    @AfterEach
    void removeOnlyThisTestFixtures() {
        for (Long id : mediaAssets) bootstrap.update("DELETE FROM product_print_assets WHERE media_asset_id = ?", id);
        for (Long id : mediaAssets) bootstrap.update("DELETE FROM media_asset WHERE id = ?", id);
        for (Integer id : products) bootstrap.update("DELETE FROM model_3d WHERE model_id = ?", id);
        for (Integer id : users) bootstrap.update("DELETE FROM users WHERE user_id = ?", id);
        mediaAssets.clear();
        products.clear();
        users.clear();
    }

    @Test
    void adminUploadsPrivateThreeMfAndReadsMetadataWithoutProviderLocation() throws Exception {
        Integer adminId = insertUser("ADMIN");
        Integer productId = insertProduct(adminId, "DESIGNER_MODEL");
        var uploaded = mvc.perform(multipart("/api/admin/products/{id}/print-assets", productId)
                        .file(new MockMultipartFile("file", "assembly.3mf", "model/3mf", validThreeMf("synthetic")))
                        .with(user(principal(adminId, Role.ADMIN))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fileName").value("assembly.3mf"))
                .andExpect(jsonPath("$.status").value("ATTACHED"))
                .andExpect(jsonPath("$.byteCount").isNumber())
                .andReturn();
        String body = uploaded.getResponse().getContentAsString();
        assertThat(body).doesNotContain("secureUrl", "publicId", "print-files", "res.cloudinary.com");

        Long assetId = bootstrap.queryForObject(
                "SELECT id FROM media_asset WHERE media_kind = 'PRINT_3MF' ORDER BY id DESC LIMIT 1", Long.class);
        mediaAssets.add(assetId);
        assertThat(bootstrap.queryForObject("SELECT delivery_type FROM media_asset WHERE id = ?", String.class, assetId))
                .isEqualTo("authenticated");
        assertThat(bootstrap.queryForObject("SELECT resource_type FROM media_asset WHERE id = ?", String.class, assetId))
                .isEqualTo("raw");
        assertThat(bootstrap.queryForObject("SELECT state FROM media_asset WHERE id = ?", String.class, assetId))
                .isEqualTo("ATTACHED");
        assertThat(bootstrap.queryForObject("SELECT uploaded_by FROM product_print_assets WHERE media_asset_id = ?",
                Integer.class, assetId)).isEqualTo(adminId);
        assertThat(bootstrap.queryForObject("SELECT count(*) FROM product_print_assets WHERE product_id = ? AND media_asset_id = ?",
                Integer.class, productId, assetId)).isEqualTo(1);

        mvc.perform(multipart("/api/admin/products/{id}/print-assets", productId)
                        .file(new MockMultipartFile("file", "assembly-revision.3mf", "application/octet-stream",
                                validThreeMf("synthetic-revision")))
                        .with(user(principal(adminId, Role.ADMIN))))
                .andExpect(status().isCreated());
        Long secondAssetId = bootstrap.queryForObject(
                "SELECT id FROM media_asset WHERE media_kind = 'PRINT_3MF' AND id <> ? ORDER BY id DESC LIMIT 1",
                Long.class, assetId);
        mediaAssets.add(secondAssetId);

        var history = mvc.perform(get("/api/admin/products/{id}/print-assets", productId)
                        .with(user(principal(adminId, Role.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].fileName", org.hamcrest.Matchers.containsInAnyOrder(
                        "assembly.3mf", "assembly-revision.3mf")))
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(2)))
                .andReturn();
        assertThat(history.getResponse().getContentAsString())
                .doesNotContain("secureUrl", "publicId", "print-files", "res.cloudinary.com");
    }

    @Test
    void deniesNonAdminAndRejectsUnsupportedProductOrMalformedFileBeforeStorage() throws Exception {
        Integer ownerId = insertUser("ADMIN");
        Integer productId = insertProduct(ownerId, "DESIGNER_MODEL");
        Integer aiProductId = insertProduct(ownerId, "AI_MODEL");
        var validFile = new MockMultipartFile("file", "part.3mf", "model/3mf", validThreeMf("part"));

        mvc.perform(multipart("/api/admin/products/{id}/print-assets", productId).file(validFile))
                .andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/admin/products/{id}/print-assets", productId).file(validFile)
                        .with(user(principal(insertUser("USER"), Role.USER))))
                .andExpect(status().isForbidden());
        mvc.perform(multipart("/api/admin/products/{id}/print-assets", aiProductId).file(validFile)
                        .with(user(principal(ownerId, Role.ADMIN))))
                .andExpect(status().isNotFound());
        mvc.perform(multipart("/api/admin/products/{id}/print-assets", 999999).file(validFile)
                        .with(user(principal(ownerId, Role.ADMIN))))
                .andExpect(status().isNotFound());
        mvc.perform(multipart("/api/admin/products/{id}/print-assets", productId)
                        .file(new MockMultipartFile("file", "broken.3mf", "model/3mf", new byte[]{1, 2, 3}))
                        .with(user(principal(ownerId, Role.ADMIN))))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(get("/api/admin/products/999999/print-assets")
                        .with(user(principal(ownerId, Role.ADMIN))))
                .andExpect(status().isNotFound());
        assertThat(storage.uploads()).isZero();
    }

    @Test
    void providerFailureRequestsCleanupForUnattachedPrintAsset() throws Exception {
        Integer adminId = insertUser("ADMIN");
        Integer productId = insertProduct(adminId, "DESIGNER_MODEL");
        storage.failNextUpload();

        mvc.perform(multipart("/api/admin/products/{id}/print-assets", productId)
                        .file(new MockMultipartFile("file", "failed.3mf", "model/3mf", validThreeMf("failed")))
                        .with(user(principal(adminId, Role.ADMIN))))
                .andExpect(status().isServiceUnavailable());

        Long id = bootstrap.queryForObject("SELECT id FROM media_asset WHERE media_kind='PRINT_3MF' ORDER BY id DESC LIMIT 1",
                Long.class);
        mediaAssets.add(id);
        bootstrap.update("UPDATE media_asset SET lease_until = CURRENT_TIMESTAMP - INTERVAL '10 minutes' WHERE id = ?", id);
        lifecycle.recoverStaleUploadIntents();
        assertThat(bootstrap.queryForObject("SELECT state FROM media_asset WHERE id=?", String.class, id))
                .isEqualTo("CLEANUP_PENDING");
        assertThat(bootstrap.queryForObject("SELECT count(*) FROM product_print_assets WHERE media_asset_id=?",
                Integer.class, id)).isZero();
    }

    private static byte[] validThreeMf(String model) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("3D/3dmodel.model"));
            zip.write(("<model>" + model + "</model>").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private Integer insertUser(String role) {
        Integer id = bootstrap.queryForObject("""
                INSERT INTO users(name,email,password,role,status)
                VALUES ('Print Asset Test', ?, 'test-only', ?, 'ACTIVE') RETURNING user_id
                """, Integer.class, "print-asset-" + UUID.randomUUID() + "@example.invalid", role);
        users.add(id);
        return id;
    }

    private Integer insertProduct(Integer userId, String sourceType) {
        Integer id = bootstrap.queryForObject("""
                INSERT INTO model_3d(source_type,user_id,name,model_status)
                VALUES (?, ?, 'Synthetic print test product', 'ACTIVE') RETURNING model_id
                """, Integer.class, sourceType, userId);
        products.add(id);
        return id;
    }

    private UserPrincipal principal(Integer id, Role role) {
        return new UserPrincipal(User.builder().id(id).name("Print Asset Test").email("print-asset-test@example.invalid")
                .password("test-only").role(role).status(UserStatus.ACTIVE).build());
    }

    private static void provisionRoles() {
        for (String role : new String[]{"admin3d_owner", "admin3d_lock_owner", "admin3d_command_owner", "payos_trust_owner"}) {
            bootstrap.execute("CREATE ROLE " + role + " NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
        }
        bootstrap.execute("CREATE ROLE app_runtime LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '"
                + RUNTIME_PASSWORD + "'");
        bootstrap.execute("GRANT admin3d_owner, admin3d_lock_owner, admin3d_command_owner, payos_trust_owner TO "
                + POSTGRES.getUsername() + " WITH INHERIT FALSE, SET TRUE");
        bootstrap.execute("GRANT USAGE, CREATE ON SCHEMA public TO admin3d_owner, admin3d_lock_owner, admin3d_command_owner, payos_trust_owner");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StorageConfiguration {
        @Bean
        @Primary
        RecordingStorage printAssetTestStorage() {
            return new RecordingStorage();
        }
    }

    static class RecordingStorage implements MediaStorageService {
        private final AtomicInteger uploadCount = new AtomicInteger();
        private final AtomicBoolean failUpload = new AtomicBoolean();

        @Override
        public MediaUploadResult upload(MediaUploadIntent intent, ValidatedMedia media) {
            uploadCount.incrementAndGet();
            if (failUpload.getAndSet(false)) throw new MediaStorageException(MediaStorageException.Reason.PROVIDER_UNAVAILABLE);
            return new MediaUploadResult(MediaProvider.CLOUDINARY, "test-cloud", "fake-asset-" + intent.mediaAssetId(),
                    intent.publicId(), intent.mediaKind(), intent.resourceType(), intent.deliveryType(), media.format(),
                    media.byteCount(), 1L, "https://res.cloudinary.com/test-cloud/raw/authenticated/v1/" + intent.publicId());
        }

        @Override
        public DeleteResult delete(MediaAssetIdentity identity) {
            return DeleteResult.DELETED;
        }

        void reset() {
            uploadCount.set(0);
            failUpload.set(false);
        }

        void failNextUpload() {
            failUpload.set(true);
        }

        int uploads() {
            return uploadCount.get();
        }
    }
}
