package com.exe.morphia3d.service.admin3d.mesh;

import com.exe.morphia3d.config.CloudinaryIdentityProperties;
import com.exe.morphia3d.controller.admin3d.AdminProductMeshController;
import com.exe.morphia3d.controller.admin3d.AdminProductMeshExceptionHandler;
import com.exe.morphia3d.dto.admin3d.mesh.MeshConfigurationRequest;
import com.exe.morphia3d.dto.admin3d.mesh.MeshConfigurationResponse;
import com.exe.morphia3d.dto.request.DesignerModelRequest;
import com.exe.morphia3d.dto.response.ModelResponse;
import com.exe.morphia3d.enums.MediaAssetState;
import com.exe.morphia3d.enums.MediaKind;
import com.exe.morphia3d.enums.MediaProvider;
import com.exe.morphia3d.enums.ModelType;
import com.exe.morphia3d.entity.*;
import com.exe.morphia3d.repository.*;
import com.exe.morphia3d.service.glb.GlbStructure;
import com.exe.morphia3d.service.DeleteResult;
import com.exe.morphia3d.service.MediaAssetCleanupLease;
import com.exe.morphia3d.service.MediaAssetIdentity;
import com.exe.morphia3d.service.MediaAssetLifecycleService;
import com.exe.morphia3d.service.MediaAssetOperationLease;
import com.exe.morphia3d.service.MediaLifecycleProperties;
import com.exe.morphia3d.service.MediaStorageProperties;
import com.exe.morphia3d.service.MediaStorageService;
import com.exe.morphia3d.service.MediaAssetIntentSpec;
import com.exe.morphia3d.service.DesignerModelPersistenceService;
import com.exe.morphia3d.service.MediaUploadIntent;
import com.exe.morphia3d.service.MediaUploadResult;
import com.exe.morphia3d.service.admin3d.mesh.AdminProductMeshService;
import com.exe.morphia3d.service.admin3d.mesh.MeshConfigurationRequestReader;
import com.exe.morphia3d.security.UserPrincipal;
import com.exe.morphia3d.service.ValidatedMedia;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.hibernate.jpa.HibernatePersistenceProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = false)
class MeshConfigurationPersistenceServicePostgresTest {
    private static final String IMAGE = "postgres@sha256:721873c34ceb9f8d8fc265984940dc982404c105f19ad51be9fdc5970a6080ea";
    private static final String MIGRATOR_PASSWORD = "mesh_cas_migrator_test_only";
    private static final String RUNTIME_PASSWORD = "mesh_cas_runtime_test_only";
    private static final String MATERIAL_ADMIN_PASSWORD = "mesh_cas_material_admin_test_only";
    private static final String MIGRATOR_APP_NAME = "mesh-cas-migrator-lock-probe";
    private static final String CONFIG_APP_NAME = "mesh-cas-config-writer";
    private static final String MATERIAL_APP_NAME = "mesh-cas-material-writer";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(IMAGE)
            .withDatabaseName("mesh_configuration_cas_test")
            .withUsername("postgres")
            .withPassword("mesh_cas_bootstrap_test_only");

    private static JdbcTemplate bootstrap;
    private static JdbcTemplate runtime;
    private static JdbcTemplate materialAdmin;
    private static JdbcTemplate migrator;
    private static DataSource runtimeDataSource;
    private static DataSource materialAdminDataSource;
    private static DataSource migratorDataSource;
    private static EntityManagerFactory entityManagerFactory;
    private static EntityManagerFactory bootstrapEntityManagerFactory;
    private static EntityManager bootstrapEntityManager;
    private static PlatformTransactionManager transactionManager;
    private static PlatformTransactionManager bootstrapJpaTransactionManager;
    private static PlatformTransactionManager materialAdminTransactionManager;
    private static PlatformTransactionManager migratorTransactionManager;
    private static MeshConfigurationPersistenceService service;
    private static AdminProductMeshService adminMeshService;
    private static Admin3dLockGateway lockGateway;
    private static ProductGlbStructureSnapshotRepository snapshots;
    private static ProductGlbStructureSnapshotRepository cleanupSnapshots;
    private static ProductMeshConfigurationRevisionRepository configurations;
    private static MaterialRepository materials;
    private static MaterialColorRepository materialColors;
    private static MediaAssetRepository mediaAssets;
    private static UserRepository cleanupUsers;
    private static MediaAssetRepository cleanupMediaAssets;
    private static Model3dRepository cleanupModels;
    private static MediaAssetLifecycleService cleanupLifecycle;

    private Fixture fixture;

    @BeforeAll
    static void initializeDisposablePostgres() {
        POSTGRES.start();
        DataSource bootstrapDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        bootstrap = new JdbcTemplate(bootstrapDataSource);
        provisionRoles();
        migrateThroughV14();
        bootstrap.execute("GRANT REFERENCES ON TABLE public.model_3d, public.media_asset, public.materials, public.users TO admin3d_owner");
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), "admin3d_migrator", MIGRATOR_PASSWORD)
                .locations("classpath:db/migration").schemas("public").defaultSchema("public")
                .cleanDisabled(true).load().migrate();
        grantTestRuntimePrivileges();

        runtimeDataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_runtime", RUNTIME_PASSWORD);
        runtime = new JdbcTemplate(runtimeDataSource);
        migratorDataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "admin3d_migrator", MIGRATOR_PASSWORD);
        migrator = new JdbcTemplate(migratorDataSource);
        migratorTransactionManager = new DataSourceTransactionManager(migratorDataSource);
        materialAdminDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "material_admin_test", MATERIAL_ADMIN_PASSWORD);
        materialAdmin = new JdbcTemplate(materialAdminDataSource);
        materialAdminTransactionManager = new DataSourceTransactionManager(materialAdminDataSource);
        LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        factory.setPersistenceProviderClass(HibernatePersistenceProvider.class);
        factory.setDataSource(runtimeDataSource);
        factory.setPackagesToScan("com.exe.morphia3d.entity");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of(
                "hibernate.hbm2ddl.auto", "validate",
                "hibernate.default_schema", "public",
                "hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect"));
        factory.afterPropertiesSet();
        entityManagerFactory = factory.getObject();
        transactionManager = new JpaTransactionManager(entityManagerFactory);

        LocalContainerEntityManagerFactoryBean bootstrapFactory = new LocalContainerEntityManagerFactoryBean();
        bootstrapFactory.setPersistenceProviderClass(HibernatePersistenceProvider.class);
        bootstrapFactory.setDataSource(bootstrapDataSource);
        bootstrapFactory.setPackagesToScan("com.exe.morphia3d.entity");
        bootstrapFactory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        bootstrapFactory.setJpaPropertyMap(Map.of(
                "hibernate.hbm2ddl.auto", "validate",
                "hibernate.default_schema", "public",
                "hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect"));
        bootstrapFactory.afterPropertiesSet();
        bootstrapEntityManagerFactory = bootstrapFactory.getObject();
        bootstrapJpaTransactionManager = new JpaTransactionManager(bootstrapEntityManagerFactory);
        bootstrapEntityManager = SharedEntityManagerCreator.createSharedEntityManager(bootstrapEntityManagerFactory);
        JpaRepositoryFactory cleanupRepositoryFactory = new JpaRepositoryFactory(bootstrapEntityManager);
        cleanupModels = cleanupRepositoryFactory.getRepository(Model3dRepository.class);
        cleanupMediaAssets = cleanupRepositoryFactory.getRepository(MediaAssetRepository.class);
        ModelImageRepository cleanupImages = cleanupRepositoryFactory.getRepository(ModelImageRepository.class);
        cleanupUsers = cleanupRepositoryFactory.getRepository(UserRepository.class);
        cleanupSnapshots = cleanupRepositoryFactory.getRepository(ProductGlbStructureSnapshotRepository.class);
        ProductMeshConfigurationRevisionRepository cleanupConfigurations =
                cleanupRepositoryFactory.getRepository(ProductMeshConfigurationRevisionRepository.class);
        CloudinaryIdentityProperties cleanupCloudIdentity = new CloudinaryIdentityProperties();
        cleanupCloudIdentity.setCloudName("local");
        cleanupLifecycle = new MediaAssetLifecycleService(cleanupMediaAssets, cleanupModels, cleanupImages,
                cleanupSnapshots, cleanupConfigurations, cleanupCloudIdentity, new MediaStorageProperties(),
                new MediaLifecycleProperties(), Clock.systemUTC(), bootstrapEntityManager);

        EntityManager sharedEntityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
        JpaRepositoryFactory repositoryFactory = new JpaRepositoryFactory(sharedEntityManager);
        Model3dRepository products = repositoryFactory.getRepository(Model3dRepository.class);
        mediaAssets = repositoryFactory.getRepository(MediaAssetRepository.class);
        materials = repositoryFactory.getRepository(MaterialRepository.class);
        materialColors = repositoryFactory.getRepository(MaterialColorRepository.class);
        snapshots = repositoryFactory.getRepository(ProductGlbStructureSnapshotRepository.class);
        configurations = repositoryFactory.getRepository(ProductMeshConfigurationRevisionRepository.class);
        lockGateway = new Admin3dLockGateway(sharedEntityManager);
        service = new MeshConfigurationPersistenceService(products, mediaAssets, materials, materialColors,
                snapshots, configurations, new MeshConfigurationValidator(), lockGateway, sharedEntityManager);
        adminMeshService = new AdminProductMeshService(products, mediaAssets, snapshots, service, transactionManager);
    }

    @AfterAll
    static void closeEntityManagerFactory() {
        if (entityManagerFactory != null && entityManagerFactory.isOpen()) entityManagerFactory.close();
        if (bootstrapEntityManagerFactory != null && bootstrapEntityManagerFactory.isOpen()) {
            bootstrapEntityManagerFactory.close();
        }
    }

    @BeforeEach
    void createFixtures() {
        int userId = bootstrap.queryForObject(
                "INSERT INTO users(name,email,password,role,status) VALUES (?,?,?,'ADMIN','ACTIVE') RETURNING user_id",
                Integer.class, "Mesh CAS test", "mesh-cas-" + UUID.randomUUID() + "@example.invalid", "test-only");
        int colorId = bootstrap.queryForObject(
                "INSERT INTO colors(name,hex_code) VALUES (?,?) RETURNING color_id",
                Integer.class, "mc-" + UUID.randomUUID(), "#123456");
        int secondColorId = bootstrap.queryForObject(
                "INSERT INTO colors(name,hex_code) VALUES (?,?) RETURNING color_id",
                Integer.class, "mc2-" + UUID.randomUUID(), "#654321");
        int materialId = bootstrap.queryForObject(
                "INSERT INTO materials(name,price_per_gram,stock_quantity,unit,is_available) VALUES (?,?,0,'g','AVAILABLE') RETURNING material_id",
                Integer.class, "Mesh CAS material " + UUID.randomUUID(), 1.0d);
        bootstrap.update("INSERT INTO material_colors(material_id,color_id,quantity) VALUES (?,?,0),(?,?,0)",
                materialId, colorId, materialId, secondColorId);
        UUID operationId = UUID.randomUUID();
        long mediaId = insertAsset(operationId, "initial");
        int productId = insertProduct(userId, mediaId);
        UUID snapshotId = UUID.randomUUID();
        fixture = new Fixture(userId, productId, mediaId, operationId, snapshotId,
                materialId, colorId, secondColorId);
        insertSnapshot(productId, mediaId, snapshotId, 1, ProductGlbStructureSnapshot.CURRENT_PARSER_VERSION, structureJson());
    }

    @Test
    void firstAndSubsequentCasRevisionsAreContinuousAndReadsReturnLatest() {
        MeshConfigurationResponse first = put(0, fixture.snapshotId(), fixture.colorId());
        MeshConfigurationResponse second = put(1, fixture.snapshotId(), fixture.secondColorId());
        assertThat(first.revision()).isEqualTo(1L);
        assertThat(second.revision()).isEqualTo(2L);
        assertThat(get().revision()).isEqualTo(2L);
        assertThat(countConfigurations()).isEqualTo(2);
    }

    @Test
    void adminMeshHttpPutAndGetUseRestrictedRuntimeAndImmutablePostgresHistory() throws Exception {
        MockMvc mvc = adminMeshMvc();
        setAdminPrincipal();
        try {
            transaction(TransactionDefinition.ISOLATION_REPEATABLE_READ, () -> {
                mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/api/admin/products/{id}/mesh-configurations", fixture.productId()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.configured").value(false))
                        .andExpect(jsonPath("$.revision").value(0));
                return null;
            });
            transaction(TransactionDefinition.ISOLATION_REPEATABLE_READ, () -> {
                mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/api/admin/products/{id}/mesh-structure", fixture.productId()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.mediaAssetId").value(fixture.mediaId()))
                        .andExpect(jsonPath("$.primitiveOccurrences[0].nodeIndex").value(0))
                        .andExpect(jsonPath("$.images").isArray())
                        .andExpect(jsonPath("$.images[0].uriSource").value(true))
                        .andExpect(jsonPath("$.images[0].name").value("embedded-private-image"))
                        .andExpect(jsonPath("$.providerUrl").doesNotExist())
                        .andExpect(jsonPath("$.cloudName").doesNotExist())
                        .andExpect(jsonPath("$.publicId").doesNotExist())
                        .andExpect(jsonPath("$.path").doesNotExist())
                        .andExpect(jsonPath("$.data").doesNotExist())
                        .andExpect(jsonPath("$.images[0].uri").doesNotExist());
                return null;
            });
            String request = JSON.writeValueAsString(request(0, fixture.snapshotId(), fixture.colorId()));
            transaction(TransactionDefinition.ISOLATION_READ_COMMITTED, () -> {
                mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .put("/api/admin/products/{id}/mesh-configurations", fixture.productId())
                                .contentType("application/json").content(request))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.configured").value(true))
                        .andExpect(jsonPath("$.revision").value(1));
                return null;
            });
            assertThat(countConfigurations()).isEqualTo(1);
            assertThat(bootstrap.queryForObject("SELECT has_table_privilege('app_runtime','public.model_3d','UPDATE')",
                    Boolean.class)).isFalse();
            assertThat(bootstrap.queryForObject("SELECT has_table_privilege('app_runtime','public.media_asset','UPDATE')",
                    Boolean.class)).isFalse();
            assertThat(bootstrap.queryForObject("SELECT has_table_privilege('app_runtime','public.materials','UPDATE')",
                    Boolean.class)).isFalse();
            transaction(TransactionDefinition.ISOLATION_REPEATABLE_READ, () -> {
                mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/api/admin/products/{id}/mesh-configurations", fixture.productId()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.configured").value(true))
                        .andExpect(jsonPath("$.revision").value(1));
                return null;
            });
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void concurrentAdminMeshHttpCasWritersProduceOneRevisionAndOneConflict() throws Exception {
        MockMvc mvc = adminMeshMvc();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Integer> writer = () -> {
                setAdminPrincipal();
                ready.countDown();
                await(start);
                try {
                    return transaction(TransactionDefinition.ISOLATION_READ_COMMITTED, () ->
                            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                            .put("/api/admin/products/{id}/mesh-configurations", fixture.productId())
                                            .contentType("application/json")
                                            .content(JSON.writeValueAsString(request(0, fixture.snapshotId(), fixture.colorId()))))
                                    .andReturn().getResponse().getStatus());
                } finally {
                    SecurityContextHolder.clearContext();
                }
            };
            Future<Integer> first = pool.submit(writer);
            Future<Integer> second = pool.submit(writer);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Integer> statuses = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
            assertThat(countConfigurations()).isEqualTo(1);
        } finally {
            start.countDown();
            pool.shutdownNow();
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void adminMeshHttpPutRollsBackRevisionWithCallerTransaction() throws Exception {
        MockMvc mvc = adminMeshMvc();
        setAdminPrincipal();
        try {
            assertThatThrownBy(() -> transaction(TransactionDefinition.ISOLATION_READ_COMMITTED, () -> {
                mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .put("/api/admin/products/{id}/mesh-configurations", fixture.productId())
                                .contentType("application/json")
                                .content(JSON.writeValueAsString(request(0, fixture.snapshotId(), fixture.colorId()))))
                        .andExpect(status().isOk());
                throw new IllegalStateException("force outer transaction rollback");
            })).isInstanceOf(IllegalStateException.class).hasMessageContaining("force outer");
            assertThat(countConfigurations()).isZero();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void missingMaterialLockSqlStateMapsToSanitizedHttp422WithoutRevisionInsert() throws Exception {
        int missingMaterialId = Integer.MAX_VALUE;
        assertThat(runtime.queryForObject("SELECT EXISTS(SELECT 1 FROM materials WHERE material_id=?)",
                Boolean.class, missingMaterialId)).isFalse();
        assertSqlState("P0002", () -> runtime.queryForObject(
                "SELECT public.admin3d_lock_material(?)", Integer.class, missingMaterialId));

        assertThatThrownBy(() -> transaction(TransactionDefinition.ISOLATION_READ_COMMITTED, () -> {
            lockGateway.lockMaterial(missingMaterialId);
            return null;
        })).isInstanceOf(MeshConfigurationException.MaterialNotFound.class)
                .satisfies(error -> assertThat(findSqlState(error)).isEqualTo("P0002"));

        AdminProductMeshService transactionalService = new AdminProductMeshService(null, null, null, service,
                transactionManager) {
            @Override
            public MeshConfigurationResponse putConfiguration(Integer productId, Integer trustedCreatedBy,
                                                               MeshConfigurationRequest request) {
                return transaction(TransactionDefinition.ISOLATION_READ_COMMITTED,
                        () -> service.putConfiguration(productId, trustedCreatedBy, request));
            }
        };
        MockMvc mvc = adminMeshMvc(transactionalService);
        setAdminPrincipal();
        MeshConfigurationRequest request = new MeshConfigurationRequest(fixture.mediaId(), fixture.snapshotId(), 0L,
                missingMaterialId, List.of(new MeshConfigurationRequest.Mesh(0, 0, 0, true,
                fixture.colorId(), List.of(fixture.colorId()))));
        assertThatThrownBy(() -> transaction(TransactionDefinition.ISOLATION_READ_COMMITTED,
                () -> service.putConfiguration(fixture.productId(), fixture.userId(), request)))
                .isInstanceOf(MeshConfigurationException.MaterialNotFound.class)
                .satisfies(error -> assertThat(findSqlState(error)).isEqualTo("P0002"));
        assertThat(countConfigurations()).isZero();
        try {
            var result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .put("/api/admin/products/{id}/mesh-configurations", fixture.productId())
                            .contentType("application/json").content(JSON.writeValueAsString(request)))
                    .andReturn();
            assertThat(result.getResponse().getStatus()).withFailMessage("HTTP response: %s",
                    result.getResponse().getContentAsString()).isEqualTo(422);
            assertThat(result.getResponse().getContentAsString()).contains("MATERIAL_NOT_FOUND",
                    "The selected material was not found.").doesNotContain("P0002");
        } finally {
            SecurityContextHolder.clearContext();
        }
        assertThat(countConfigurations()).isZero();
    }

    private MockMvc adminMeshMvc() {
        return adminMeshMvc(adminMeshService);
    }

    private MockMvc adminMeshMvc(AdminProductMeshService meshService) {
        return MockMvcBuilders.standaloneSetup(new AdminProductMeshController(meshService,
                        new MeshConfigurationRequestReader()))
                .setControllerAdvice(new AdminProductMeshExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    private void setAdminPrincipal() {
        UserPrincipal principal = new UserPrincipal(cleanupUsers.findById(fixture.userId()).orElseThrow());
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, "test-only",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        SecurityContextHolder.setContext(context);
    }

    @Test
    void snapshotEnabledPersistenceCommitsProductImagesAttachmentAndSnapshotAtomically() {
        MediaAssetOperationLease ready = createReadyProductOperation("snapshot-atomic-commit");
        DesignerModelPersistenceService persistence = bootstrapDesignerPersistence();
        DesignerModelRequest metadata = new DesignerModelRequest();
        metadata.setName("Snapshot persisted product");

        ModelResponse response = persistence.persistWithSnapshot(new com.exe.morphia3d.security.UserPrincipal(
                        cleanupUsers.findById(fixture.userId()).orElseThrow()), metadata, ready,
                ProductGlbStructureSnapshot.CURRENT_PARSER_VERSION, snapshotDocument());

        int productId = response.getId();
        Long modelMediaId = ready.mediaAssetIds().get(0);
        ProductGlbStructureSnapshot snapshot = snapshots
                .findFirstByProductIdAndMediaAssetIdOrderBySnapshotRevisionDesc(productId, modelMediaId).orElseThrow();
        assertThat(snapshot.getSnapshotRevision()).isEqualTo(1L);
        assertThat(snapshot.getParserVersion()).isEqualTo(ProductGlbStructureSnapshot.CURRENT_PARSER_VERSION);
        assertThat(snapshot.getCreatedBy()).isEqualTo(fixture.userId());
        assertThat(snapshot.readStructure().nodes()).hasSize(1);
        assertThat(bootstrap.queryForObject("SELECT COUNT(*) FROM model_images WHERE model_id=?", Integer.class,
                productId)).isEqualTo(1);
        assertThat(bootstrap.queryForList("SELECT state FROM media_asset WHERE id IN (?,?) ORDER BY id", String.class,
                ready.mediaAssetIds().get(0), ready.mediaAssetIds().get(1))).containsOnly("ATTACHED");
        assertThat(bootstrap.queryForObject("SELECT media_asset_id FROM model_3d WHERE model_id=?", Long.class,
                productId)).isEqualTo(modelMediaId);
    }

    @Test
    void snapshotDatabaseFailureRollsBackProductImagesAndMediaAttachment() {
        MediaAssetOperationLease ready = createReadyProductOperation("snapshot-atomic-rollback");
        DesignerModelPersistenceService persistence = bootstrapDesignerPersistence();
        DesignerModelRequest metadata = new DesignerModelRequest();
        metadata.setName("Snapshot rollback product");
        String constraint = "ck_test_reject_snapshot_" + UUID.randomUUID().toString().replace("-", "");
        bootstrap.execute("ALTER TABLE product_glb_structure_snapshots ADD CONSTRAINT " + constraint
                + " CHECK (snapshot_revision <> 1) NOT VALID");
        try {
            assertThatThrownBy(() -> persistence.persistWithSnapshot(
                    new com.exe.morphia3d.security.UserPrincipal(cleanupUsers.findById(fixture.userId()).orElseThrow()),
                    metadata, ready, ProductGlbStructureSnapshot.CURRENT_PARSER_VERSION, snapshotDocument()))
                    .isInstanceOf(com.exe.morphia3d.service.MediaPersistenceException.class)
                    .satisfies(failure -> assertThat(
                            ((com.exe.morphia3d.service.MediaPersistenceException) failure).isSafeToCompensate()).isTrue());
            assertThat(bootstrap.queryForObject("SELECT COUNT(*) FROM model_3d WHERE name=?", Integer.class,
                    "Snapshot rollback product")).isZero();
            assertThat(bootstrap.queryForObject("SELECT COUNT(*) FROM model_images WHERE model_id IN "
                    + "(SELECT model_id FROM model_3d WHERE name=?)", Integer.class,
                    "Snapshot rollback product")).isZero();
            assertThat(bootstrap.queryForList("SELECT state FROM media_asset WHERE id IN (?,?) ORDER BY id", String.class,
                    ready.mediaAssetIds().get(0), ready.mediaAssetIds().get(1))).containsOnly("READY");
            assertThat(snapshots.existsByMediaAssetId(ready.mediaAssetIds().get(0))).isFalse();
        } finally {
            bootstrap.execute("ALTER TABLE product_glb_structure_snapshots DROP CONSTRAINT " + constraint);
        }
    }

    @Test
    void staleCleanupOperationCannotCreateSnapshotOrAttachModel() {
        MediaAssetOperationLease ready = createReadyProductOperation("snapshot-stale-operation");
        bootstrapTransaction(() -> cleanupLifecycle.requestCleanup(ready, ready.mediaAssetIds()));
        DesignerModelPersistenceService persistence = bootstrapDesignerPersistence();
        DesignerModelRequest metadata = new DesignerModelRequest();
        metadata.setName("Stale snapshot product");

        assertThatThrownBy(() -> persistence.persistWithSnapshot(
                new com.exe.morphia3d.security.UserPrincipal(cleanupUsers.findById(fixture.userId()).orElseThrow()),
                metadata, ready, ProductGlbStructureSnapshot.CURRENT_PARSER_VERSION, snapshotDocument()))
                .isInstanceOf(com.exe.morphia3d.service.MediaPersistenceException.class)
                .satisfies(failure -> assertThat(
                        ((com.exe.morphia3d.service.MediaPersistenceException) failure).isSafeToCompensate()).isTrue());
        assertThat(bootstrap.queryForObject("SELECT COUNT(*) FROM model_3d WHERE name=?", Integer.class,
                "Stale snapshot product")).isZero();
        assertThat(snapshots.existsByMediaAssetId(ready.mediaAssetIds().get(0))).isFalse();
        assertThat(bootstrap.queryForList("SELECT state FROM media_asset WHERE id IN (?,?) ORDER BY id", String.class,
                ready.mediaAssetIds().get(0), ready.mediaAssetIds().get(1))).containsOnly("CLEANUP_PENDING");
    }

    @Test
    void configurationWriterWinsLockThenHistoricalGlbCannotBeCleanedAfterReplacement() throws Exception {
        CountDownLatch configurationInserted = new CountDownLatch(1);
        CountDownLatch allowConfigurationCommit = new CountDownLatch(1);
        CountDownLatch cleanupStarted = new CountDownLatch(1);
        AtomicInteger configurationPid = new AtomicInteger();
        AtomicInteger cleanupPid = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Long> writer = pool.submit(() -> transaction(TransactionDefinition.ISOLATION_READ_COMMITTED, () -> {
                runtime.execute("SET LOCAL application_name='mesh-history-writer-wins'");
                configurationPid.set(runtime.queryForObject("SELECT pg_backend_pid()", Integer.class));
                long revision = service.putConfiguration(fixture.productId(), fixture.userId(),
                        request(0, fixture.snapshotId(), fixture.colorId())).revision();
                configurationInserted.countDown();
                await(allowConfigurationCommit);
                return revision;
            }));
            assertThat(configurationInserted.await(10, TimeUnit.SECONDS)).isTrue();

            Future<MediaAssetCleanupLease> cleanup = pool.submit(() -> bootstrapTransaction(() -> {
                bootstrap.execute("SET LOCAL application_name='mesh-history-cleanup-waiter'");
                cleanupPid.set(bootstrap.queryForObject("SELECT pg_backend_pid()", Integer.class));
                cleanupStarted.countDown();
                return cleanupLifecycle.claimCleanup(fixture.mediaId(), "history-cleanup");
            }));
            assertThat(cleanupStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(configurationPid.get()).isNotEqualTo(cleanupPid.get());
            assertThat(awaitLockWait(cleanupPid.get())).isTrue();

            allowConfigurationCommit.countDown();
            assertThat(writer.get(10, TimeUnit.SECONDS)).isEqualTo(1L);
            assertThat(cleanup.get(10, TimeUnit.SECONDS)).isNull();
            assertThat(countConfigurations()).isEqualTo(1);

            long replacementMediaId = insertAsset(UUID.randomUUID(), "history-replacement");
            bootstrap.update("UPDATE model_3d SET media_asset_id=? WHERE model_id=?",
                    replacementMediaId, fixture.productId());
            setCleanupPending(fixture.mediaId());

            assertThat(bootstrap.queryForObject("SELECT media_asset_id FROM model_3d WHERE model_id=?",
                    Long.class, fixture.productId())).isEqualTo(replacementMediaId);
            assertThat(snapshots.existsByMediaAssetId(fixture.mediaId())).isTrue();
            assertThat(configurations.existsByMediaAssetId(fixture.mediaId())).isTrue();
            assertThat(bootstrapTransaction(() -> cleanupLifecycle.claimCleanup(fixture.mediaId(), "history-cleanup")))
                    .isNull();
            assertThat(bootstrap.queryForObject("SELECT state FROM media_asset WHERE id=?", String.class,
                    fixture.mediaId())).isEqualTo("CLEANUP_PENDING");
        } finally {
            allowConfigurationCommit.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void cleanupRefreshesOperationStateAfterWaitingForRowLock() throws Exception {
        UUID operationId = UUID.randomUUID();
        long candidateId = insertCleanupCandidate(operationId, "post-lock-refresh");
        CountDownLatch leaseWriterUpdated = new CountDownLatch(1);
        CountDownLatch allowLeaseWriterCommit = new CountDownLatch(1);
        CountDownLatch cleanupStarted = new CountDownLatch(1);
        AtomicInteger leaseWriterPid = new AtomicInteger();
        AtomicInteger cleanupPid = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> leaseWriter = pool.submit(() -> bootstrapTransaction(() -> {
                leaseWriterPid.set(bootstrap.queryForObject("SELECT pg_backend_pid()", Integer.class));
                bootstrap.update("UPDATE media_asset SET state='CLEANUP_LEASED',lease_owner=?,lease_token=?,"
                                + "lease_until=? WHERE id=?",
                        "another-worker", UUID.randomUUID(), java.sql.Timestamp.from(Instant.now().plusSeconds(60)), candidateId);
                leaseWriterUpdated.countDown();
                await(allowLeaseWriterCommit);
                return null;
            }));
            assertThat(leaseWriterUpdated.await(10, TimeUnit.SECONDS)).isTrue();

            Future<MediaAssetCleanupLease> cleanup = pool.submit(() -> bootstrapTransaction(() -> {
                cleanupPid.set(bootstrap.queryForObject("SELECT pg_backend_pid()", Integer.class));
                cleanupStarted.countDown();
                return cleanupLifecycle.claimCleanup(candidateId, "cleanup-after-refresh");
            }));
            assertThat(cleanupStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(leaseWriterPid.get()).isNotEqualTo(cleanupPid.get());
            assertThat(awaitLockWait(cleanupPid.get())).isTrue();

            allowLeaseWriterCommit.countDown();
            leaseWriter.get(10, TimeUnit.SECONDS);
            assertThat(cleanup.get(10, TimeUnit.SECONDS)).isNull();
            assertThat(bootstrap.queryForObject("SELECT state FROM media_asset WHERE id=?", String.class,
                    candidateId)).isEqualTo("CLEANUP_LEASED");
        } finally {
            allowLeaseWriterCommit.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void historyLookupFailurePropagatesAndDoesNotClaimCleanup() {
        long candidateId = insertCleanupCandidate(UUID.randomUUID(), "history-query-failure");
        bootstrap.execute("ALTER TABLE product_mesh_configuration_revisions "
                + "RENAME TO product_mesh_configuration_revisions_unavailable");
        try {
            assertThatThrownBy(() -> bootstrapTransaction(() -> cleanupLifecycle.claimCleanup(candidateId,
                    "cleanup-query-failure"))).isInstanceOf(RuntimeException.class);
            assertThat(bootstrap.queryForObject("SELECT state FROM media_asset WHERE id=?", String.class,
                    candidateId)).isEqualTo("CLEANUP_PENDING");
        } finally {
            bootstrap.execute("ALTER TABLE product_mesh_configuration_revisions_unavailable "
                    + "RENAME TO product_mesh_configuration_revisions");
        }
    }

    @Test
    void finishCleanupRechecksHistoricalReferenceBeforeMarkingAssetDeleted() {
        put(0, fixture.snapshotId(), fixture.colorId());
        setCleanupPending(fixture.mediaId());
        UUID token = UUID.randomUUID();
        bootstrap.update("UPDATE media_asset SET state='CLEANUP_LEASED',lease_owner=?,lease_token=?,lease_until=? WHERE id=?",
                "finish-check", token, java.sql.Timestamp.from(Instant.now().plusSeconds(60)), fixture.mediaId());
        MediaAssetCleanupLease lease = new MediaAssetCleanupLease(fixture.mediaId(), fixture.operationId(), token);

        assertThatThrownBy(() -> bootstrapTransaction(() -> {
            cleanupLifecycle.finishCleanup(lease, DeleteResult.DELETED);
            return null;
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("no longer safe");
        assertThat(bootstrap.queryForObject("SELECT state FROM media_asset WHERE id=?", String.class,
                fixture.mediaId())).isEqualTo("CLEANUP_LEASED");
    }

    @Test
    void applicationReferenceWriterWinsAndCleanupWaitsThenRejects() throws Exception {
        ReadyOperation ready = createReadyOperation("cleanup-waits-for-reference-writer");
        CountDownLatch attached = new CountDownLatch(1);
        CountDownLatch allowWriterCommit = new CountDownLatch(1);
        CountDownLatch cleanupStarted = new CountDownLatch(1);
        AtomicInteger writerPid = new AtomicInteger();
        AtomicInteger cleanupPid = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> writer = pool.submit(() -> bootstrapTransaction(() -> {
                bootstrap.execute("SET LOCAL application_name='media-reference-writer-wins'");
                writerPid.set(bootstrap.queryForObject("SELECT pg_backend_pid()", Integer.class));
                Model3d product = saveApplicationModelReference(ready, "writer-wins-product");
                cleanupLifecycle.attach(ready.operation(), ready.operation().mediaAssetIds());
                attached.countDown();
                await(allowWriterCommit);
                return product.getId();
            }));
            assertThat(attached.await(10, TimeUnit.SECONDS)).isTrue();

            Future<String> cleanup = pool.submit(() -> bootstrapTransaction(() -> {
                bootstrap.execute("SET LOCAL application_name='cleanup-after-reference-writer'");
                cleanupPid.set(bootstrap.queryForObject("SELECT pg_backend_pid()", Integer.class));
                cleanupStarted.countDown();
                try {
                    cleanupLifecycle.requestCleanup(ready.operation(), ready.operation().mediaAssetIds());
                    return "CLEANUP_REQUESTED";
                } catch (IllegalStateException referenced) {
                    return "REFERENCE_REJECTED";
                }
            }));
            assertThat(cleanupStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(writerPid.get()).isNotEqualTo(cleanupPid.get());
            assertThat(awaitLockWait(cleanupPid.get())).isTrue();

            allowWriterCommit.countDown();
            assertThat(writer.get(10, TimeUnit.SECONDS)).isPositive();
            assertThat(cleanup.get(10, TimeUnit.SECONDS)).isEqualTo("REFERENCE_REJECTED");
            assertThat(bootstrap.queryForObject("SELECT state FROM media_asset WHERE id=?", String.class,
                    ready.mediaAssetId())).isEqualTo("ATTACHED");
            assertThat(bootstrap.queryForObject("SELECT COUNT(*) FROM model_3d WHERE media_asset_id=?",
                    Integer.class, ready.mediaAssetId())).isEqualTo(1);
        } finally {
            allowWriterCommit.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void cleanupWinsLockAndApplicationReferenceWriterRollsBackAfterWait() throws Exception {
        ReadyOperation ready = createReadyOperation("cleanup-wins-reference-writer");
        CountDownLatch cleanupRequested = new CountDownLatch(1);
        CountDownLatch allowCleanupCommit = new CountDownLatch(1);
        CountDownLatch writerStarted = new CountDownLatch(1);
        AtomicInteger cleanupPid = new AtomicInteger();
        AtomicInteger writerPid = new AtomicInteger();
        String modelName = "cleanup-wins-product-" + UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> cleanup = pool.submit(() -> bootstrapTransaction(() -> {
                cleanupPid.set(bootstrap.queryForObject("SELECT pg_backend_pid()", Integer.class));
                assertThat(cleanupLifecycle.requestCleanup(ready.operation(), ready.operation().mediaAssetIds()))
                        .isEqualTo(1);
                cleanupRequested.countDown();
                await(allowCleanupCommit);
                return null;
            }));
            assertThat(cleanupRequested.await(10, TimeUnit.SECONDS)).isTrue();

            Future<String> writer = pool.submit(() -> {
                try {
                    return bootstrapTransaction(() -> {
                        bootstrap.execute("SET LOCAL application_name='media-reference-writer-after-cleanup'");
                        writerPid.set(bootstrap.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        writerStarted.countDown();
                    Model3d product = Model3d.builder().sourceType(ModelType.DESIGNER_MODEL)
                            .user(cleanupUsers.findById(fixture.userId()).orElseThrow())
                            .name(modelName).filePath3d(ready.upload().publicId())
                            .mediaAsset(cleanupMediaAssets.findById(ready.mediaAssetId()).orElseThrow()).build();
                    cleanupModels.saveAndFlush(product);
                    cleanupLifecycle.attach(ready.operation(), ready.operation().mediaAssetIds());
                    return "ATTACHED";
                    });
                } catch (IllegalStateException cleanupWon) {
                    return "REJECTED";
                }
            });
            assertThat(writerStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(cleanupPid.get()).isNotEqualTo(writerPid.get());
            assertThat(awaitLockWait(writerPid.get())).isTrue();

            allowCleanupCommit.countDown();
            cleanup.get(10, TimeUnit.SECONDS);
            assertThat(writer.get(10, TimeUnit.SECONDS)).isEqualTo("REJECTED");
            assertThat(bootstrap.queryForObject("SELECT COUNT(*) FROM model_3d WHERE name=?",
                    Integer.class, modelName)).isZero();
            assertThat(bootstrap.queryForObject("SELECT state FROM media_asset WHERE id=?", String.class,
                    ready.mediaAssetId())).isEqualTo("CLEANUP_PENDING");
            assertThat(bootstrap.queryForObject("SELECT COUNT(*) FROM product_mesh_configuration_revisions "
                    + "WHERE media_asset_id=?", Integer.class, ready.mediaAssetId())).isZero();
        } finally {
            allowCleanupCommit.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void cleanupLeaseDeletionBarrierRejectsApplicationReferenceWriter() throws Exception {
        ReadyOperation ready = createReadyOperation("cleanup-provider-barrier");
        assertThat(bootstrapTransaction(() -> cleanupLifecycle.requestCleanup(ready.operation(),
                ready.operation().mediaAssetIds()))).isEqualTo(1);
        setCleanupDue(ready.mediaAssetId());
        MediaAssetCleanupLease lease = bootstrapTransaction(() -> cleanupLifecycle.claimCleanup(
                ready.mediaAssetId(), "barrier-cleaner"));
        assertThat(lease).isNotNull();
        MediaAssetIdentity identity = bootstrapTransaction(() -> cleanupLifecycle.verifyCleanupLease(lease));

        BarrierStorage storage = new BarrierStorage();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> delete = pool.submit(() -> storage.delete(identity));
            assertThat(storage.deleteStarted.await(10, TimeUnit.SECONDS)).isTrue();

            String modelName = "provider-barrier-product-" + UUID.randomUUID();
            assertThatThrownBy(() -> bootstrapTransaction(() -> {
                Model3d product = Model3d.builder().sourceType(ModelType.DESIGNER_MODEL)
                        .user(cleanupUsers.findById(fixture.userId()).orElseThrow())
                        .name(modelName).filePath3d(ready.upload().publicId())
                        .mediaAsset(cleanupMediaAssets.findById(ready.mediaAssetId()).orElseThrow()).build();
                cleanupModels.saveAndFlush(product);
                cleanupLifecycle.attach(ready.operation(), ready.operation().mediaAssetIds());
                return null;
            })).isInstanceOf(IllegalStateException.class);
            assertThat(bootstrap.queryForObject("SELECT COUNT(*) FROM model_3d WHERE name=?",
                    Integer.class, modelName)).isZero();
            assertThat(bootstrap.queryForObject("SELECT COUNT(*) FROM product_mesh_configuration_revisions "
                    + "WHERE media_asset_id=?", Integer.class, ready.mediaAssetId())).isZero();

            storage.releaseDelete.countDown();
            delete.get(10, TimeUnit.SECONDS);
            bootstrapTransaction(() -> {
                cleanupLifecycle.finishCleanup(lease, DeleteResult.DELETED);
                return null;
            });
            assertThat(storage.deletes.get()).isEqualTo(1);
            assertThat(bootstrap.queryForObject("SELECT state FROM media_asset WHERE id=?", String.class,
                    ready.mediaAssetId())).isEqualTo("DELETED");
        } finally {
            storage.releaseDelete.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentCasWritersSerializeAndOnlyOneExpectedRevisionZeroSucceeds() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<String> first = pool.submit(() -> concurrentPut(start, fixture.colorId()));
            Future<String> second = pool.submit(() -> concurrentPut(start, fixture.secondColorId()));
            start.countDown();
            List<String> results = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertThat(results).containsExactlyInAnyOrder("PASS", "CONFLICT");
            assertThat(countConfigurations()).isEqualTo(1);
            assertThat(latestConfiguration().getConfigRevision()).isEqualTo(1L);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void snapshotReplacementKeepsRevisionSequenceAndOldSnapshotWriteConflicts() {
        put(0, fixture.snapshotId(), fixture.colorId());
        UUID replacementId = UUID.randomUUID();
        insertSnapshot(fixture.productId(), fixture.mediaId(), replacementId, 2,
                ProductGlbStructureSnapshot.CURRENT_PARSER_VERSION, structureJson());

        MeshConfigurationResponse read = get();
        assertThat(read.configured()).isFalse();
        assertThat(read.revision()).isEqualTo(1L);
        assertThat(read.meshes()).isEmpty();
        assertThatThrownBy(() -> put(1, fixture.snapshotId(), fixture.colorId()))
                .isInstanceOf(MeshConfigurationException.Conflict.class);
        assertThat(put(1, replacementId, fixture.colorId()).revision()).isEqualTo(2L);
    }

    @Test
    void staleMediaAndExpectedRevisionConflictWithoutCreatingHistory() {
        MeshConfigurationRequest staleMedia = new MeshConfigurationRequest(fixture.mediaId() + 1000,
                fixture.snapshotId(), 0L, fixture.materialId(),
                List.of(new MeshConfigurationRequest.Mesh(0, 0, 0, true, fixture.colorId(), List.of(fixture.colorId()))));
        assertThatThrownBy(() -> transaction(TransactionDefinition.ISOLATION_READ_COMMITTED,
                () -> service.putConfiguration(fixture.productId(), fixture.userId(), staleMedia)))
                .isInstanceOf(MeshConfigurationException.Conflict.class);
        put(0, fixture.snapshotId(), fixture.colorId());
        assertThatThrownBy(() -> put(0, fixture.snapshotId(), fixture.colorId()))
                .isInstanceOf(MeshConfigurationException.Conflict.class);
        assertThat(countConfigurations()).isEqualTo(1);
    }

    @Test
    void incompatibleColorRollsBackAndDoesNotLeaveARevisionGap() {
        assertThatThrownBy(() -> put(0, fixture.snapshotId(), Integer.MAX_VALUE))
                .isInstanceOf(MeshConfigurationException.Invalid.class);
        assertThat(countConfigurations()).isZero();
        assertThat(put(0, fixture.snapshotId(), fixture.colorId()).revision()).isEqualTo(1L);
        assertThat(countConfigurations()).isEqualTo(1);
    }

    @Test
    void runtimeHasSelectAndApprovedFunctionLocksWithoutParentUpdatePrivileges() {
        assertThat(runtime.queryForObject("SELECT current_user", String.class)).isEqualTo("app_runtime");
        assertThat(runtime.queryForObject("SELECT COUNT(*) FROM model_3d WHERE model_id=?", Integer.class,
                fixture.productId())).isEqualTo(1);
        assertThat(runtime.queryForObject("SELECT COUNT(*) FROM media_asset WHERE id=?", Integer.class,
                fixture.mediaId())).isEqualTo(1);
        assertThat(runtime.queryForObject("SELECT COUNT(*) FROM materials WHERE material_id=?", Integer.class,
                fixture.materialId())).isEqualTo(1);
        assertThat(runtime.queryForObject("SELECT COUNT(*) FROM material_colors WHERE material_id=?", Integer.class,
                fixture.materialId())).isEqualTo(2);

        assertSqlState("42501", () -> runtime.queryForObject("SELECT model_id FROM model_3d WHERE model_id=? FOR UPDATE",
                Integer.class, fixture.productId()));
        assertSqlState("42501", () -> runtime.queryForObject("SELECT id FROM media_asset WHERE id=? FOR UPDATE",
                Long.class, fixture.mediaId()));
        assertSqlState("42501", () -> runtime.queryForObject("SELECT material_id FROM materials WHERE material_id=? FOR UPDATE",
                Integer.class, fixture.materialId()));

        assertThat(runtime.queryForObject("SELECT pg_backend_pid()", Integer.class)).isPositive();
        transaction(TransactionDefinition.ISOLATION_READ_COMMITTED, () -> {
            runtime.execute("SET LOCAL lock_timeout='9s'");
            String lockTimeoutBefore = runtime.queryForObject("SHOW lock_timeout", String.class);
            int runtimeBackendPid = runtime.queryForObject("SELECT pg_backend_pid()", Integer.class);
            lockGateway.lockProduct(fixture.productId());
            assertThat(lockGateway.lockMediaOperation(fixture.operationId())).isPositive();
            lockGateway.lockMaterial(fixture.materialId());
            assertThat(runtime.queryForObject("SELECT current_user", String.class)).isEqualTo("app_runtime");
            assertThat(runtime.queryForObject("SELECT pg_backend_pid()", Integer.class)).isEqualTo(runtimeBackendPid);
            assertThat(runtime.queryForObject("SHOW lock_timeout", String.class)).isEqualTo(lockTimeoutBefore);
            return null;
        });

        assertThat(runtime.queryForObject("SELECT has_table_privilege('app_runtime','public.model_3d','UPDATE')",
                Boolean.class)).isFalse();
        assertThat(runtime.queryForObject("SELECT has_column_privilege('app_runtime','public.model_3d','model_id','UPDATE')",
                Boolean.class)).isFalse();
        assertThat(runtime.queryForObject("SELECT has_column_privilege('app_runtime','public.model_3d','name','UPDATE')",
                Boolean.class)).isFalse();
        assertThat(runtime.queryForObject("SELECT has_column_privilege('app_runtime','public.media_asset','id','UPDATE')",
                Boolean.class)).isFalse();
        assertThat(runtime.queryForObject("SELECT has_column_privilege('app_runtime','public.media_asset','state','UPDATE')",
                Boolean.class)).isFalse();
        assertThat(runtime.queryForObject("SELECT has_column_privilege('app_runtime','public.materials','material_id','UPDATE')",
                Boolean.class)).isFalse();
        assertThat(runtime.queryForObject("SELECT has_column_privilege('app_runtime','public.materials','is_available','UPDATE')",
                Boolean.class)).isFalse();
        assertThat(runtime.queryForObject("SELECT pg_has_role('app_runtime','admin3d_owner','MEMBER')",
                Boolean.class)).isFalse();
        assertThat(runtime.queryForObject("SELECT has_schema_privilege('app_runtime','public','CREATE')",
                Boolean.class)).isFalse();
        assertThat(runtime.queryForObject("SELECT rolsuper OR rolcreatedb OR rolcreaterole FROM pg_roles WHERE rolname='app_runtime'",
                Boolean.class)).isFalse();
        assertThat(runtime.queryForObject("SELECT pg_has_role('app_runtime','admin3d_lock_owner','MEMBER')",
                Boolean.class)).isFalse();
        assertThat(runtime.queryForObject("SELECT has_schema_privilege('admin3d_lock_owner','public','CREATE')",
                Boolean.class)).isFalse();
        assertThat(runtime.queryForObject("SELECT bool_and(NOT EXISTS (SELECT 1 FROM aclexplode("
                + "COALESCE(p.proacl,acldefault('f',p.proowner))) acl WHERE acl.grantee=0 "
                + "AND acl.privilege_type='EXECUTE')) FROM pg_proc p WHERE p.proname IN "
                + "('admin3d_lock_product','admin3d_lock_media_operation','admin3d_lock_material')", Boolean.class)).isTrue();
        assertThat(runtime.queryForObject("SELECT has_function_privilege('app_runtime','public.admin3d_lock_product(integer)','EXECUTE')",
                Boolean.class)).isTrue();
        assertThat(runtime.queryForObject("SELECT has_function_privilege('app_runtime','public.admin3d_lock_media_operation(uuid)','EXECUTE')",
                Boolean.class)).isTrue();
        assertThat(runtime.queryForObject("SELECT has_function_privilege('app_runtime','public.admin3d_lock_material(integer)','EXECUTE')",
                Boolean.class)).isTrue();

        assertSqlState("42501", () -> runtime.update("UPDATE model_3d SET name=name WHERE model_id=?", fixture.productId()));
        assertSqlState("42501", () -> runtime.update("UPDATE model_3d SET model_id=model_id WHERE model_id=?", fixture.productId()));
        assertSqlState("42501", () -> runtime.update("UPDATE media_asset SET state=state WHERE id=?", fixture.mediaId()));
        assertSqlState("42501", () -> runtime.update("UPDATE media_asset SET id=id WHERE id=?", fixture.mediaId()));
        assertSqlState("42501", () -> runtime.update("UPDATE materials SET is_available=is_available WHERE material_id=?", fixture.materialId()));
        assertSqlState("42501", () -> runtime.update("UPDATE materials SET material_id=material_id WHERE material_id=?", fixture.materialId()));
        assertSqlState("42501", () -> runtime.update("DELETE FROM material_colors WHERE material_id=?", fixture.materialId()));
        assertSqlState("42501", () -> runtime.execute("SET ROLE admin3d_lock_owner"));
        assertThat(materialAdmin.queryForObject("SELECT rolsuper OR rolcreatedb OR rolcreaterole FROM pg_roles WHERE rolname='material_admin_test'",
                Boolean.class)).isFalse();
        assertThat(materialAdmin.queryForObject("SELECT pg_has_role('material_admin_test','admin3d_owner','MEMBER')",
                Boolean.class)).isFalse();
        assertThat(materialAdmin.queryForObject("SELECT has_schema_privilege('material_admin_test','public','CREATE')",
                Boolean.class)).isFalse();
        assertThat(materialAdmin.queryForObject("SELECT current_user", String.class)).isEqualTo("material_admin_test");
        assertThat(materialAdmin.queryForObject("SELECT has_table_privilege('material_admin_test','public.material_colors','DELETE')",
                Boolean.class)).isTrue();
        assertThat(materialAdmin.queryForObject("SELECT has_table_privilege('material_admin_test','public.material_colors','INSERT')",
                Boolean.class)).isFalse();
        assertThat(materialAdmin.queryForObject("SELECT has_column_privilege('material_admin_test','public.materials','is_available','UPDATE')",
                Boolean.class)).isTrue();
        assertThat(materialAdmin.queryForObject("SELECT has_column_privilege('material_admin_test','public.materials','material_id','UPDATE')",
                Boolean.class)).isFalse();
        assertSqlState("42501", () -> materialAdmin.update("INSERT INTO material_colors(material_id,color_id,quantity) VALUES (?,?,0)",
                fixture.materialId(), fixture.colorId()));
        assertSqlState("42501", () -> materialAdmin.update("UPDATE materials SET name=name WHERE material_id=?",
                fixture.materialId()));
    }

    @Test
    void runtimeCannotMutateHistoryOrChangeItsSchemaOrTriggers() {
        put(0, fixture.snapshotId(), fixture.colorId());
        UUID configurationId = runtime.queryForObject("SELECT configuration_revision_id "
                + "FROM product_mesh_configuration_revisions WHERE product_id=?", UUID.class, fixture.productId());
        assertSqlState("42501", () -> runtime.update("UPDATE product_glb_structure_snapshots "
                + "SET parser_version=parser_version WHERE structure_snapshot_id=?", fixture.snapshotId()));
        assertSqlState("42501", () -> runtime.update("DELETE FROM product_mesh_configuration_revisions "
                + "WHERE configuration_revision_id=?", configurationId));
        assertSqlState("42501", () -> runtime.execute("TRUNCATE product_glb_structure_snapshots"));
        assertSqlState("42501", () -> runtime.execute("TRUNCATE product_mesh_configuration_revisions"));
        assertSqlState("42501", () -> runtime.execute("ALTER TABLE product_glb_structure_snapshots ADD COLUMN runtime_probe INTEGER"));
        assertSqlState("42501", () -> runtime.execute("DROP TABLE product_mesh_configuration_revisions"));
        assertSqlState("42501", () -> runtime.execute("ALTER TABLE product_glb_structure_snapshots DISABLE TRIGGER ALL"));
        assertThat(runtime.queryForObject("SELECT COUNT(*) FROM product_mesh_configuration_revisions "
                + "WHERE configuration_revision_id=?", Integer.class, configurationId)).isEqualTo(1);
    }

    @Test
    void restrictedLockFunctionsAreSecurityDefinerOwnedByNoLoginRole() {
        assertThat(runtime.queryForObject("SELECT rolcanlogin OR rolinherit OR rolsuper OR rolcreatedb OR rolcreaterole OR rolbypassrls "
                + "FROM pg_roles WHERE rolname='admin3d_lock_owner'", Boolean.class)).isFalse();
        assertThat(runtime.queryForObject("SELECT has_column_privilege('admin3d_lock_owner','public.model_3d','model_id','UPDATE') "
                + "AND has_column_privilege('admin3d_lock_owner','public.media_asset','id','UPDATE') "
                + "AND has_column_privilege('admin3d_lock_owner','public.materials','material_id','UPDATE')", Boolean.class)).isTrue();
        assertThat(runtime.queryForObject("SELECT has_column_privilege('admin3d_lock_owner','public.model_3d','name','UPDATE') "
                + "OR has_column_privilege('admin3d_lock_owner','public.media_asset','state','UPDATE') "
                + "OR has_column_privilege('admin3d_lock_owner','public.materials','is_available','UPDATE')", Boolean.class)).isFalse();
        assertThat(runtime.queryForObject("SELECT pg_has_role('app_runtime','admin3d_lock_owner','MEMBER')", Boolean.class)).isFalse();
        assertThat(runtime.queryForObject("SELECT bool_and(prosecdef) FROM pg_proc WHERE proname IN "
                + "('admin3d_lock_product','admin3d_lock_media_operation','admin3d_lock_material')", Boolean.class)).isTrue();
        assertThat(runtime.queryForObject("SELECT bool_and(pg_get_userbyid(proowner)='admin3d_lock_owner') FROM pg_proc "
                + "WHERE proname IN ('admin3d_lock_product','admin3d_lock_media_operation','admin3d_lock_material')", Boolean.class)).isTrue();
        assertThat(runtime.queryForObject("SELECT bool_and(proconfig @> ARRAY['search_path=pg_catalog']::text[] AND "
                + "proconfig @> ARRAY['lock_timeout=1500ms']::text[]) FROM pg_proc WHERE proname IN "
                + "('admin3d_lock_product','admin3d_lock_media_operation','admin3d_lock_material')", Boolean.class)).isTrue();
        assertThat(runtime.queryForObject("SELECT rolsuper OR rolcreatedb OR rolcreaterole FROM pg_roles WHERE rolname='admin3d_migrator'",
                Boolean.class)).isFalse();
        assertThat(migratorTransaction(() -> {
            migrator.execute("SET LOCAL ROLE admin3d_lock_owner");
            return migrator.queryForObject("SELECT current_user", String.class);
        })).isEqualTo("admin3d_lock_owner");
    }

    @Test
    void missingRowsAndLockTimeoutFailClosedWithoutHistoryInsert() throws Exception {
        assertThatThrownBy(() -> transaction(TransactionDefinition.ISOLATION_READ_COMMITTED, () -> {
            lockGateway.lockProduct(Integer.MAX_VALUE);
            return null;
        })).isInstanceOf(MeshConfigurationException.NotFound.class);
        assertThatThrownBy(() -> transaction(TransactionDefinition.ISOLATION_READ_COMMITTED, () -> {
            lockGateway.lockMaterial(Integer.MAX_VALUE);
            return null;
        })).isInstanceOf(MeshConfigurationException.MaterialNotFound.class)
                .satisfies(error -> assertThat(findSqlState(error)).isEqualTo("P0002"));
        assertThatThrownBy(() -> transaction(TransactionDefinition.ISOLATION_READ_COMMITTED, () -> {
            lockGateway.lockMediaOperation(UUID.randomUUID());
            return null;
        })).isInstanceOf(MeshConfigurationException.Conflict.class);

        CountDownLatch materialLocked = new CountDownLatch(1);
        CountDownLatch releaseMaterial = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = pool.submit(() -> materialAdminTransaction(() -> {
                materialAdmin.queryForObject("SELECT material_id FROM materials WHERE material_id=? FOR UPDATE",
                        Integer.class, fixture.materialId());
                materialLocked.countDown();
                await(releaseMaterial);
                return null;
            }));
            assertThat(materialLocked.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> put(0, fixture.snapshotId(), fixture.colorId()))
                    .isInstanceOf(MeshConfigurationException.Conflict.class)
                    .hasMessageContaining("lock timed out")
                    .satisfies(error -> assertThat(findSqlState(error)).isEqualTo("55P03"));
            assertThat(countConfigurations()).isZero();
            releaseMaterial.countDown();
            holder.get(10, TimeUnit.SECONDS);
        } finally {
            releaseMaterial.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void mediaOperationFunctionLocksSameRowsAsExistingMediaLifecycleQueries() throws Exception {
        CountDownLatch runtimeLocked = new CountDownLatch(1);
        CountDownLatch releaseRuntime = new CountDownLatch(1);
        CountDownLatch migratorStarted = new CountDownLatch(1);
        CountDownLatch migratorBackendReady = new CountDownLatch(1);
        AtomicInteger runtimePid = new AtomicInteger();
        AtomicInteger migratorPid = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> functionLock = pool.submit(() -> transaction(TransactionDefinition.ISOLATION_READ_COMMITTED, () -> {
                runtime.execute("SET LOCAL application_name='mesh-cas-function-media-lock'");
                runtimePid.set(runtime.queryForObject("SELECT pg_backend_pid()", Integer.class));
                assertThat(lockGateway.lockMediaOperation(fixture.operationId())).isPositive();
                runtimeLocked.countDown();
                await(releaseRuntime);
                return null;
            }));
            assertThat(runtimeLocked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> existingLockQuery = pool.submit(() -> {
                migratorStarted.countDown();
                return migratorTransaction(() -> {
                migrator.execute("SET LOCAL application_name='mesh-cas-existing-media-lock'");
                migratorPid.set(migrator.queryForObject("SELECT pg_backend_pid()", Integer.class));
                migratorBackendReady.countDown();
                migrator.query("SELECT id FROM public.media_asset WHERE operation_id=? ORDER BY id FOR UPDATE",
                        rs -> { while (rs.next()) { /* consume locked operation members */ } }, fixture.operationId());
                return null;
            });
            });
            assertThat(migratorStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(migratorBackendReady.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(migratorPid.get()).isNotEqualTo(runtimePid.get());
            assertThat(awaitLockWait(migratorPid.get())).isTrue();
            releaseRuntime.countDown();
            existingLockQuery.get(10, TimeUnit.SECONDS);
            functionLock.get(10, TimeUnit.SECONDS);
        } finally {
            releaseRuntime.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void productFunctionLockIsHeldUntilCallerRollback() throws Exception {
        CountDownLatch runtimeLocked = new CountDownLatch(1);
        CountDownLatch releaseRuntime = new CountDownLatch(1);
        CountDownLatch migratorStarted = new CountDownLatch(1);
        AtomicInteger runtimePid = new AtomicInteger();
        AtomicInteger migratorPid = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> functionLock = pool.submit(() -> {
                TransactionTemplate template = new TransactionTemplate(transactionManager);
                template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
                try {
                    template.execute(status -> {
                        runtime.execute("SET LOCAL application_name='mesh-cas-function-product-lock'");
                        runtimePid.set(runtime.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        lockGateway.lockProduct(fixture.productId());
                        runtimeLocked.countDown();
                        await(releaseRuntime);
                        throw new MeshConfigurationException.Invalid("force rollback after acquiring row lock");
                    });
                    throw new AssertionError("Intentional rollback exception was not propagated");
                } catch (MeshConfigurationException.Invalid expected) {
                    // The caller transaction rolls back while the waiting peer owns no lock yet.
                }
            });
            assertThat(runtimeLocked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> existingLockQuery = pool.submit(() -> migratorTransaction(() -> {
                migrator.execute("SET LOCAL application_name='mesh-cas-existing-product-lock'");
                migratorPid.set(migrator.queryForObject("SELECT pg_backend_pid()", Integer.class));
                migratorStarted.countDown();
                migrator.queryForObject("SELECT model_id FROM public.model_3d WHERE model_id=? FOR UPDATE",
                        Integer.class, fixture.productId());
                return null;
            }));
            assertThat(migratorStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(runtimePid.get()).isNotEqualTo(migratorPid.get());
            assertThat(awaitLockWait(migratorPid.get())).isTrue();
            releaseRuntime.countDown();
            functionLock.get(10, TimeUnit.SECONDS);
            existingLockQuery.get(10, TimeUnit.SECONDS);
        } finally {
            releaseRuntime.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void materialColorReplacementWaitsForMaterialLockAndPutReadsCommittedAssociations() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch allowColorChangeCommit = new CountDownLatch(1);
        CountDownLatch putStarted = new CountDownLatch(1);
        CountDownLatch putBackendReady = new CountDownLatch(1);
        AtomicInteger materialPid = new AtomicInteger();
        AtomicInteger configPid = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> update = pool.submit(() -> materialAdminTransaction(() -> {
                materialAdmin.execute("SET LOCAL application_name='" + MATERIAL_APP_NAME + "'");
                int pid = materialAdmin.queryForObject("SELECT pg_backend_pid()", Integer.class);
                materialPid.set(pid);
                materialAdmin.queryForObject("SELECT material_id FROM materials WHERE material_id=? FOR UPDATE",
                        Integer.class, fixture.materialId());
                materialAdmin.update("DELETE FROM material_colors WHERE material_id=? AND color_id=?",
                        fixture.materialId(), fixture.colorId());
                locked.countDown();
                await(allowColorChangeCommit);
                return pid;
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<String> put = pool.submit(() -> {
                putStarted.countDown();
                try {
                    transaction(TransactionDefinition.ISOLATION_READ_COMMITTED, () -> {
                        runtime.execute("SET LOCAL application_name='" + CONFIG_APP_NAME + "'");
                        configPid.set(runtime.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        putBackendReady.countDown();
                        service.putConfiguration(fixture.productId(), fixture.userId(), request(0,
                                fixture.snapshotId(), fixture.colorId()));
                        return null;
                    });
                    return "PASS";
                } catch (MeshConfigurationException.Invalid expected) {
                    return "INVALID";
                }
            });
            assertThat(putStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(putBackendReady.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(configPid.get()).isNotEqualTo(materialPid.get());
            assertThat(awaitLockWait(configPid.get())).isTrue();
            allowColorChangeCommit.countDown();
            assertThat(update.get(10, TimeUnit.SECONDS)).isEqualTo(materialPid.get());
            assertThat(put.get(10, TimeUnit.SECONDS)).isEqualTo("INVALID");
            assertThat(countConfigurations()).isZero();
        } finally {
            allowColorChangeCommit.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void putRefreshesSelectedAssetAfterWaitingForOperationLock() throws Exception {
        UUID isolatedOperationId = UUID.randomUUID();
        bootstrap.update("UPDATE media_asset SET operation_id=? WHERE id=?", isolatedOperationId, fixture.mediaId());
        CountDownLatch externalAssetLockedAndChanged = new CountDownLatch(1);
        CountDownLatch releaseExternalWriter = new CountDownLatch(1);
        CountDownLatch putStarted = new CountDownLatch(1);
        CountDownLatch putBackendReady = new CountDownLatch(1);
        AtomicInteger externalPid = new AtomicInteger();
        AtomicInteger putPid = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> externalWriter = pool.submit(() -> bootstrapTransaction(() -> {
                bootstrap.execute("SET LOCAL application_name='mesh-cas-unsafe-asset-writer'");
                externalPid.set(bootstrap.queryForObject("SELECT pg_backend_pid()", Integer.class));
                assertThat(bootstrap.queryForObject("SELECT id FROM media_asset WHERE id=? FOR UPDATE",
                        Long.class, fixture.mediaId())).isEqualTo(fixture.mediaId());
                bootstrap.update("UPDATE media_asset SET state='CLEANUP_PENDING' WHERE id=?", fixture.mediaId());
                externalAssetLockedAndChanged.countDown();
                await(releaseExternalWriter);
                return null;
            }));
            assertThat(externalAssetLockedAndChanged.await(10, TimeUnit.SECONDS)).isTrue();

            Future<String> put = pool.submit(() -> {
                putStarted.countDown();
                try {
                    return transaction(TransactionDefinition.ISOLATION_READ_COMMITTED, () -> {
                        runtime.execute("SET LOCAL application_name='mesh-cas-unsafe-asset-put'");
                        putPid.set(runtime.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        putBackendReady.countDown();
                        service.putConfiguration(fixture.productId(), fixture.userId(),
                                request(0, fixture.snapshotId(), fixture.colorId()));
                        return "ACCEPTED";
                    });
                } catch (MeshConfigurationException.Conflict expected) {
                    return "CONFLICT";
                }
            });
            assertThat(putStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(putBackendReady.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(putPid.get()).isNotEqualTo(externalPid.get());
            assertThat(awaitLockWait(putPid.get())).isTrue();

            releaseExternalWriter.countDown();
            externalWriter.get(10, TimeUnit.SECONDS);
            assertThat(put.get(10, TimeUnit.SECONDS)).isEqualTo("CONFLICT");
            assertThat(countConfigurations()).isZero();
            assertThat(bootstrap.queryForObject("SELECT state FROM media_asset WHERE id=?", String.class,
                    fixture.mediaId())).isEqualTo("CLEANUP_PENDING");
        } finally {
            releaseExternalWriter.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            bootstrap.update("UPDATE media_asset SET state='ATTACHED',operation_id=? WHERE id=?",
                    fixture.operationId(), fixture.mediaId());
        }
    }

    @Test
    void configurationLockWinsThenMaterialWriterCommitsWithoutChangingHistory() throws Exception {
        CountDownLatch configurationInserted = new CountDownLatch(1);
        CountDownLatch allowConfigurationCommit = new CountDownLatch(1);
        CountDownLatch materialWriterStarted = new CountDownLatch(1);
        AtomicInteger configPid = new AtomicInteger();
        AtomicInteger materialPid = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Long> configuration = pool.submit(() -> transaction(TransactionDefinition.ISOLATION_READ_COMMITTED, () -> {
                runtime.execute("SET LOCAL application_name='" + CONFIG_APP_NAME + "'");
                configPid.set(runtime.queryForObject("SELECT pg_backend_pid()", Integer.class));
                MeshConfigurationResponse response = service.putConfiguration(fixture.productId(), fixture.userId(),
                        request(0, fixture.snapshotId(), fixture.colorId()));
                configurationInserted.countDown();
                await(allowConfigurationCommit);
                return response.revision();
            }));
            assertThat(configurationInserted.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Integer> writer = pool.submit(() -> {
                materialWriterStarted.countDown();
                return materialAdminTransaction(() -> {
                    materialAdmin.execute("SET LOCAL application_name='" + MATERIAL_APP_NAME + "'");
                    materialPid.set(materialAdmin.queryForObject("SELECT pg_backend_pid()", Integer.class));
                    materialAdmin.queryForObject("SELECT material_id FROM materials WHERE material_id=? FOR UPDATE",
                            Integer.class, fixture.materialId());
                    materialAdmin.update("DELETE FROM material_colors WHERE material_id=? AND color_id=?",
                            fixture.materialId(), fixture.colorId());
                    return materialPid.get();
                });
            });
            assertThat(materialWriterStarted.await(10, TimeUnit.SECONDS)).isTrue();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (materialPid.get() == 0 && System.nanoTime() < deadline) Thread.yield();
            assertThat(materialPid.get()).isPositive();
            assertThat(materialPid.get()).isNotEqualTo(configPid.get());
            assertThat(awaitLockWait(materialPid.get())).isTrue();

            allowConfigurationCommit.countDown();
            assertThat(configuration.get(10, TimeUnit.SECONDS)).isEqualTo(1L);
            assertThat(writer.get(10, TimeUnit.SECONDS)).isEqualTo(materialPid.get());

            assertThat(countConfigurations()).isEqualTo(1);
            assertThat(runtime.queryForObject("SELECT configuration_json #>> '{meshes,0,defaultColorId}' "
                    + "FROM product_mesh_configuration_revisions WHERE product_id=?", Integer.class, fixture.productId()))
                    .isEqualTo(fixture.colorId());
            assertThat(runtime.queryForObject("SELECT EXISTS(SELECT 1 FROM material_colors WHERE material_id=? AND color_id=?)",
                    Boolean.class, fixture.materialId(), fixture.colorId())).isFalse();
            assertThat(get().configured()).isTrue();
        } finally {
            allowConfigurationCommit.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void unavailableMaterialAndRevisionOverflowAreRejectedWithoutHistoryGap() {
        materialAdmin.update("UPDATE materials SET is_available='UNAVAILABLE' WHERE material_id=?", fixture.materialId());
        assertThatThrownBy(() -> put(0, fixture.snapshotId(), fixture.colorId()))
                .isInstanceOf(MeshConfigurationException.Conflict.class);
        materialAdmin.update("UPDATE materials SET is_available='AVAILABLE' WHERE material_id=?", fixture.materialId());
        insertConfiguration(1, Long.MAX_VALUE, fixture.snapshotId(), fixture.colorId());
        assertThatThrownBy(() -> put(Long.MAX_VALUE, fixture.snapshotId(), fixture.colorId()))
                .isInstanceOf(MeshConfigurationException.Conflict.class);
        assertThat(countConfigurations()).isEqualTo(1);
    }

    @Test
    void missingUnsupportedAndCorruptLatestHistoryFailsClosedWithoutFallback() {
        long noSnapshotMediaId = insertAsset(UUID.randomUUID(), "no-snapshot");
        int noSnapshotProductId = insertProduct(fixture.userId(), noSnapshotMediaId);
        assertThatThrownBy(() -> get(noSnapshotProductId))
                .isInstanceOf(MeshConfigurationException.Conflict.class);
        UUID unsupportedId = UUID.randomUUID();
        insertSnapshot(fixture.productId(), fixture.mediaId(), unsupportedId, 2, "future-parser", structureJson());
        assertThatThrownBy(this::get)
                .isInstanceOf(MeshConfigurationException.Conflict.class);
        UUID validReplacementId = UUID.randomUUID();
        insertSnapshot(fixture.productId(), fixture.mediaId(), validReplacementId, 3,
                ProductGlbStructureSnapshot.CURRENT_PARSER_VERSION, structureJson());
        insertConfiguration(1, 1, validReplacementId, fixture.colorId(), "{\"schemaVersion\":99,\"meshes\":[]}");
        assertThatThrownBy(this::get)
                .isInstanceOf(MeshConfigurationException.Conflict.class);
    }

    private MeshConfigurationResponse put(long revision, UUID snapshotId, int colorId) {
        return transaction(TransactionDefinition.ISOLATION_READ_COMMITTED, () ->
                service.putConfiguration(fixture.productId(), fixture.userId(), request(revision, snapshotId, colorId)));
    }

    private MeshConfigurationResponse get() {
        return get(fixture.productId());
    }

    private MeshConfigurationResponse get(int productId) {
        return transaction(TransactionDefinition.ISOLATION_REPEATABLE_READ,
                () -> service.getLatestConfiguration(productId));
    }

    private String concurrentPut(CountDownLatch start, int colorId) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        try {
            put(0, fixture.snapshotId(), colorId);
            return "PASS";
        } catch (MeshConfigurationException.Conflict conflict) {
            return "CONFLICT";
        }
    }

    private MeshConfigurationRequest request(long revision, UUID snapshotId, int colorId) {
        return new MeshConfigurationRequest(fixture.mediaId(), snapshotId, revision, fixture.materialId(),
                List.of(new MeshConfigurationRequest.Mesh(0, 0, 0, true, colorId, List.of(colorId))));
    }

    private <T> T transaction(int isolation, java.util.concurrent.Callable<T> action) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(isolation);
        return template.execute(status -> {
            try { return action.call(); }
            catch (RuntimeException exception) { throw exception; }
            catch (Exception exception) { throw new IllegalStateException(exception); }
        });
    }

    private <T> T materialAdminTransaction(java.util.concurrent.Callable<T> action) {
        TransactionTemplate template = new TransactionTemplate(materialAdminTransactionManager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return template.execute(status -> {
            try { return action.call(); }
            catch (RuntimeException exception) { throw exception; }
            catch (Exception exception) { throw new IllegalStateException(exception); }
        });
    }

    private <T> T bootstrapTransaction(java.util.concurrent.Callable<T> action) {
        TransactionTemplate template = new TransactionTemplate(bootstrapJpaTransactionManager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return template.execute(status -> {
            try { return action.call(); }
            catch (RuntimeException exception) { throw exception; }
            catch (Exception exception) { throw new IllegalStateException(exception); }
        });
    }

    private <T> T migratorTransaction(java.util.concurrent.Callable<T> action) {
        TransactionTemplate template = new TransactionTemplate(migratorTransactionManager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return template.execute(status -> {
            try { return action.call(); }
            catch (RuntimeException exception) { throw exception; }
            catch (Exception exception) { throw new IllegalStateException(exception); }
        });
    }

    private static boolean awaitLockWait(int backendPid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Boolean waiting = bootstrap.queryForObject("SELECT EXISTS (SELECT 1 FROM pg_stat_activity "
                    + "WHERE pid=? AND state='active' AND wait_event_type='Lock')", Boolean.class, backendPid);
            if (Boolean.TRUE.equals(waiting)) return true;
            TimeUnit.MILLISECONDS.sleep(20);
        }
        return false;
    }

    private int countConfigurations() {
        return runtime.queryForObject("SELECT COUNT(*) FROM product_mesh_configuration_revisions WHERE product_id=?",
                Integer.class, fixture.productId());
    }

    private ReadyOperation createReadyOperation(String label) {
        UUID operationId = UUID.randomUUID();
        MediaAssetOperationLease operation = bootstrapTransaction(() -> cleanupLifecycle.createUploadIntents(
                operationId, List.of(new MediaAssetIntentSpec(MediaKind.MODEL_3D, "glb", 64L))));
        long mediaAssetId = operation.mediaAssetIds().get(0);
        MediaUploadIntent upload = bootstrapTransaction(() -> cleanupLifecycle.claimUpload(operation, mediaAssetId));
        bootstrapTransaction(() -> {
            cleanupLifecycle.recordUpload(upload, new MediaUploadResult(MediaProvider.CLOUDINARY, "local",
                    "provider-" + label + "-" + UUID.randomUUID(), upload.publicId(), MediaKind.MODEL_3D,
                    "raw", "upload", "glb", 64L, 1L,
                    "https://res.cloudinary.com/local/raw/upload/v1/" + upload.publicId()));
            return null;
        });
        return new ReadyOperation(operation, upload, mediaAssetId);
    }

    private MediaAssetOperationLease createReadyProductOperation(String label) {
        MediaAssetOperationLease operation = bootstrapTransaction(() -> cleanupLifecycle.createUploadIntents(
                UUID.randomUUID(), List.of(
                        new MediaAssetIntentSpec(MediaKind.MODEL_3D, "glb", 64L),
                        new MediaAssetIntentSpec(MediaKind.IMAGE, "png", 16L))));
        for (Long mediaAssetId : operation.mediaAssetIds()) {
            MediaUploadIntent intent = bootstrapTransaction(() -> cleanupLifecycle.claimUpload(operation, mediaAssetId));
            MediaAsset asset = cleanupMediaAssets.findById(mediaAssetId).orElseThrow();
            String suffix = asset.getMediaKind() == MediaKind.IMAGE ? "." + asset.getFormat() : "";
            String secureUrl = "https://res.cloudinary.com/" + asset.getCloudName() + "/"
                    + asset.getResourceType() + "/" + asset.getDeliveryType() + "/v1/"
                    + intent.publicId() + suffix;
            MediaUploadResult uploaded = new MediaUploadResult(MediaProvider.CLOUDINARY, asset.getCloudName(),
                    "provider-" + label + "-" + mediaAssetId, intent.publicId(), asset.getMediaKind(),
                    asset.getResourceType(), asset.getDeliveryType(), asset.getFormat(),
                    asset.getByteCount(), 1L, secureUrl);
            bootstrapTransaction(() -> {
                cleanupLifecycle.recordUpload(intent, uploaded);
                return null;
            });
        }
        return operation;
    }

    private DesignerModelPersistenceService bootstrapDesignerPersistence() {
        JpaRepositoryFactory factory = new JpaRepositoryFactory(bootstrapEntityManager);
        ColorRepository cleanupColors = factory.getRepository(ColorRepository.class);
        return new DesignerModelPersistenceService(bootstrapJpaTransactionManager, cleanupMediaAssets,
                cleanupModels, cleanupUsers, cleanupColors,
                model -> ModelResponse.builder().id(model.getId()).build(), cleanupLifecycle,
                cleanupSnapshots, bootstrapEntityManager);
    }

    private Model3d saveApplicationModelReference(ReadyOperation ready, String name) {
        Model3d product = Model3d.builder().sourceType(ModelType.DESIGNER_MODEL)
                .user(cleanupUsers.findById(fixture.userId()).orElseThrow())
                .name(name).filePath3d(ready.upload().publicId())
                .mediaAsset(cleanupMediaAssets.findById(ready.mediaAssetId()).orElseThrow()).build();
        return cleanupModels.saveAndFlush(product);
    }

    private void setCleanupDue(long mediaAssetId) {
        Instant due = Instant.now().minusSeconds(60);
        bootstrap.update("UPDATE media_asset SET lease_until=?,updated_at=? WHERE id=?",
                java.sql.Timestamp.from(due), java.sql.Timestamp.from(due), mediaAssetId);
    }

    private ProductMeshConfigurationRevision latestConfiguration() {
        return transaction(TransactionDefinition.ISOLATION_READ_COMMITTED, () -> configurations
                .findFirstByProductIdAndMediaAssetIdOrderByConfigRevisionDesc(fixture.productId(), fixture.mediaId()).orElseThrow());
    }

    private long insertAsset(UUID operation, String suffix) {
        long mediaId = bootstrap.queryForObject("""
                INSERT INTO media_asset(operation_id,media_kind,provider,cloud_name,public_id,resource_type,
                    delivery_type,format,byte_count,state)
                VALUES (?,'MODEL_3D','CLOUDINARY','local',?,'raw','private','glb',64,'ATTACHED') RETURNING id
                """, Long.class, operation, "mesh-cas-" + suffix + "-" + UUID.randomUUID());
        bootstrap.update("UPDATE media_asset SET public_id=?,delivery_type='upload' WHERE id=?",
                "product-media/models/" + operation + "/" + mediaId + ".glb", mediaId);
        return mediaId;
    }

    private long insertCleanupCandidate(UUID operation, String suffix) {
        long mediaId = insertAsset(operation, suffix);
        setCleanupPending(mediaId);
        return mediaId;
    }

    private void setCleanupPending(long mediaId) {
        Instant due = Instant.now().minusSeconds(60);
        bootstrap.update("UPDATE media_asset SET state='CLEANUP_PENDING',lease_owner=NULL,lease_token=NULL,"
                        + "lease_until=?,retry_count=0,updated_at=? WHERE id=?",
                java.sql.Timestamp.from(due), java.sql.Timestamp.from(due), mediaId);
    }

    private int insertProduct(int userId, long mediaId) {
        return bootstrap.queryForObject("""
                INSERT INTO model_3d(source_type,user_id,name,model_status,media_asset_id)
                VALUES ('DESIGNER_MODEL', ?, ?, 'ACTIVE', ?) RETURNING model_id
                """, Integer.class, userId, "Mesh CAS product " + UUID.randomUUID(), mediaId);
    }

    private void insertSnapshot(int productId, long mediaId, UUID snapshotId, long revision,
                                String parserVersion, JsonNode json) {
        runtime.update("""
                INSERT INTO product_glb_structure_snapshots
                    (structure_snapshot_id,product_id,media_asset_id,snapshot_revision,parser_version,structure_json,created_by)
                VALUES (?,?,?,?,?,?::jsonb,?)
                """, snapshotId, productId, mediaId, revision, parserVersion, json.toString(), fixture.userId());
    }

    private void insertConfiguration(long revision, long configRevision, UUID snapshotId, int colorId) {
        insertConfiguration(revision, configRevision, snapshotId, colorId,
                configurationJson(colorId).toString());
    }

    private void insertConfiguration(long idSuffix, long configRevision, UUID snapshotId, int colorId, String json) {
        runtime.update("""
                INSERT INTO product_mesh_configuration_revisions
                    (configuration_revision_id,product_id,media_asset_id,structure_snapshot_id,config_revision,
                     material_id,configuration_json,created_by)
                VALUES (?,?,?,?,?,?,?::jsonb,?)
                """, UUID.nameUUIDFromBytes(("mesh-cas-config-" + idSuffix + "-" + UUID.randomUUID()).getBytes()),
                fixture.productId(), fixture.mediaId(), snapshotId, configRevision, fixture.materialId(), json, fixture.userId());
    }

    private static void provisionRoles() {
        bootstrap.execute("CREATE ROLE admin3d_owner NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
        bootstrap.execute("CREATE ROLE admin3d_lock_owner NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
        bootstrap.execute("CREATE ROLE admin3d_command_owner NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
        bootstrap.execute("CREATE ROLE payos_trust_owner NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
        bootstrap.execute("CREATE ROLE admin3d_migrator LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '" + MIGRATOR_PASSWORD + "'");
        bootstrap.execute("CREATE ROLE app_runtime LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '" + RUNTIME_PASSWORD + "'");
        bootstrap.execute("CREATE ROLE material_admin_test LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '" + MATERIAL_ADMIN_PASSWORD + "'");
        bootstrap.execute("GRANT admin3d_owner TO admin3d_migrator WITH INHERIT FALSE, SET TRUE");
        bootstrap.execute("GRANT admin3d_lock_owner TO admin3d_migrator WITH INHERIT FALSE, SET TRUE");
        bootstrap.execute("GRANT admin3d_command_owner TO admin3d_migrator WITH INHERIT FALSE, SET TRUE");
        bootstrap.execute("GRANT payos_trust_owner TO admin3d_migrator WITH INHERIT FALSE, SET TRUE");
        bootstrap.execute("GRANT CONNECT ON DATABASE mesh_configuration_cas_test TO admin3d_migrator, app_runtime, material_admin_test");
        bootstrap.execute("GRANT USAGE, CREATE ON SCHEMA public TO admin3d_owner");
        bootstrap.execute("GRANT USAGE, CREATE ON SCHEMA public TO admin3d_migrator WITH GRANT OPTION");
        bootstrap.execute("GRANT USAGE ON SCHEMA public TO app_runtime");
        bootstrap.execute("GRANT USAGE ON SCHEMA public TO material_admin_test");
    }

    private static void migrateThroughV14() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), "admin3d_migrator", MIGRATOR_PASSWORD)
                .locations("classpath:db/migration").schemas("public").defaultSchema("public")
                .cleanDisabled(true).target(MigrationVersion.fromVersion("14")).load().migrate();
    }

    private static void grantTestRuntimePrivileges() {
        bootstrap.execute("GRANT REFERENCES ON TABLE public.model_3d, public.media_asset, public.materials, public.users TO admin3d_owner");
        bootstrap.execute("GRANT SELECT, INSERT ON TABLE public.product_glb_structure_snapshots, public.product_mesh_configuration_revisions TO app_runtime");
        bootstrap.execute("GRANT SELECT ON TABLE public.model_3d, public.media_asset, public.materials, public.material_colors TO app_runtime");
        bootstrap.execute("GRANT SELECT, UPDATE (is_available) ON TABLE public.materials TO material_admin_test");
        bootstrap.execute("GRANT SELECT, DELETE ON TABLE public.material_colors TO material_admin_test");
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), "admin3d_migrator", MIGRATOR_PASSWORD)
                .locations("classpath:db/migration").schemas("public").defaultSchema("public")
                .cleanDisabled(true).load().migrate();
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), "admin3d_migrator", MIGRATOR_PASSWORD)
                .locations("classpath:db/migration").schemas("public").defaultSchema("public").load().validate();
    }

    private static JsonNode structureJson() {
        GlbStructure structure = new GlbStructure(-1, List.of(),
                List.of(new GlbStructure.Node(0, "Node", 0, List.of(), List.of())),
                List.of(new GlbStructure.Mesh(0, "Mesh", List.of(
                        new GlbStructure.Primitive(0, null, List.of("POSITION"), List.of())))),
                List.of(), List.of(), List.of(new GlbStructure.Image(0, "embedded-private-image",
                        "image/png", true, null)),
                List.of(new GlbStructure.PrimitiveOccurrence(0, 0, 0, "Node", null)));
        return JSON.valueToTree(structure);
    }

    private static JsonNode snapshotDocument() {
        return structureJson();
    }

    private static JsonNode configurationJson(int colorId) {
        return JSON.createObjectNode().put("schemaVersion", 1)
                .set("meshes", JSON.createArrayNode().add(JSON.createObjectNode()
                        .put("nodeIndex", 0).put("meshIndex", 0).put("primitiveIndex", 0)
                        .put("customizable", true).put("defaultColorId", colorId)
                        .set("allowedColorIds", JSON.createArrayNode().add(colorId))));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting for test latch");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static void assertSqlState(String expected, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(DataAccessException.class)
                .satisfies(error -> assertThat(sqlState(error)).isEqualTo(expected));
    }

    private static String sqlState(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SQLException sqlException) return sqlException.getSQLState();
            current = current.getCause();
        }
        return null;
    }

    private static String findSqlState(Throwable error) {
        return sqlState(error);
    }

    private record Fixture(int userId, int productId, long mediaId, UUID operationId, UUID snapshotId,
                           int materialId, int colorId, int secondColorId) {}

    private record ReadyOperation(MediaAssetOperationLease operation, MediaUploadIntent upload, long mediaAssetId) {}

    private static final class BarrierStorage implements MediaStorageService {
        private final CountDownLatch deleteStarted = new CountDownLatch(1);
        private final CountDownLatch releaseDelete = new CountDownLatch(1);
        private final AtomicInteger deletes = new AtomicInteger();

        @Override
        public MediaUploadResult upload(MediaUploadIntent intent, ValidatedMedia media) {
            throw new UnsupportedOperationException("No provider upload is performed in this test");
        }

        @Override
        public DeleteResult delete(MediaAssetIdentity identity) {
            deletes.incrementAndGet();
            deleteStarted.countDown();
            await(releaseDelete);
            return DeleteResult.DELETED;
        }
    }
}
