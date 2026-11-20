package com.exe.morphia3d.repository;

import com.exe.morphia3d.entity.ProductGlbStructureSnapshot;
import com.exe.morphia3d.entity.ProductMeshConfigurationRevision;
import com.exe.morphia3d.service.glb.GlbStructure;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class ProductMeshHistoryPersistencePostgresTest {
    private static final String POSTGRES_IMAGE = "postgres@sha256:721873c34ceb9f8d8fc265984940dc982404c105f19ad51be9fdc5970a6080ea";
    private static final String MIGRATOR_PASSWORD = "mesh_history_migrator_test_only";
    private static final String RUNTIME_PASSWORD = "mesh_history_runtime_test_only";
    private static final String PARSER_VERSION = "glb-structure-1";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE)
            .withDatabaseName("mesh_history_persistence_test")
            .withUsername("postgres")
            .withPassword("mesh_history_bootstrap_test_only");

    private static JdbcTemplate bootstrap;
    private static JdbcTemplate runtime;
    private static EntityManagerFactory entityManagerFactory;
    private static StandardServiceRegistry serviceRegistry;
    private Fixture fixture;

    @BeforeAll
    static void migrateV15AndValidateMappings() {
        POSTGRES.start();
        DriverManagerDataSource bootstrapDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        bootstrap = new JdbcTemplate(bootstrapDataSource);
        provisionTestRoles();

        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), "admin3d_migrator", MIGRATOR_PASSWORD)
                .locations("classpath:db/migration")
                .schemas("public")
                .defaultSchema("public")
                .cleanDisabled(true)
                .target(MigrationVersion.fromVersion("14"))
                .load()
                .migrate();
        bootstrap.execute("GRANT REFERENCES ON TABLE public.model_3d, public.media_asset, public.materials, public.users TO admin3d_owner");
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), "admin3d_migrator", MIGRATOR_PASSWORD)
                .locations("classpath:db/migration")
                .schemas("public")
                .defaultSchema("public")
                .cleanDisabled(true)
                .load()
                .migrate();
        bootstrap.execute("GRANT SELECT, INSERT ON TABLE public.product_glb_structure_snapshots, public.product_mesh_configuration_revisions TO app_runtime");
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), "admin3d_migrator", MIGRATOR_PASSWORD)
                .locations("classpath:db/migration")
                .schemas("public")
                .defaultSchema("public")
                .load()
                .validate();

        DriverManagerDataSource runtimeDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "app_runtime", RUNTIME_PASSWORD);
        runtime = new JdbcTemplate(runtimeDataSource);
        serviceRegistry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.url", POSTGRES.getJdbcUrl())
                .applySetting("hibernate.connection.username", "app_runtime")
                .applySetting("hibernate.connection.password", RUNTIME_PASSWORD)
                .applySetting("hibernate.connection.driver_class", "org.postgresql.Driver")
                .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                .applySetting("hibernate.default_schema", "public")
                .applySetting("hibernate.hbm2ddl.auto", "validate")
                .build();
        SessionFactory sessionFactory = new MetadataSources(serviceRegistry)
                .addAnnotatedClass(ProductGlbStructureSnapshot.class)
                .addAnnotatedClass(ProductMeshConfigurationRevision.class)
                .buildMetadata()
                .buildSessionFactory();
        entityManagerFactory = sessionFactory;
    }

    @AfterAll
    static void closeHibernate() {
        if (entityManagerFactory != null && entityManagerFactory.isOpen()) {
            entityManagerFactory.close();
        }
        if (serviceRegistry != null) {
            StandardServiceRegistryBuilder.destroy(serviceRegistry);
        }
    }

    @BeforeEach
    void createDisposableParentRows() {
        int userId = bootstrap.queryForObject(
                "INSERT INTO users(name,email,password,role,status) VALUES (?,?,?,'ADMIN','ACTIVE') RETURNING user_id",
                Integer.class, "Mesh history test", "mesh-history-" + UUID.randomUUID() + "@example.invalid", "test-only");
        int materialId = bootstrap.queryForObject(
                "INSERT INTO materials(name,price_per_gram,stock_quantity,unit) VALUES (?,?,0,'g') RETURNING material_id",
                Integer.class, "Mesh history material " + UUID.randomUUID(), 1.0d);
        ProductAsset first = createProductAsset(userId, "first");
        ProductAsset second = createProductAsset(userId, "second");
        fixture = new Fixture(userId, materialId, first.productId(), first.mediaAssetId(),
                second.productId(), second.mediaAssetId());
    }

    @Test
    void validatesJsonbMappingsAndRoundTripsLatestRowsWithoutLosingSnapshotIdentity() {
        GlbStructure structure = structure();
        UUID snapshotOneId = UUID.randomUUID();
        UUID snapshotLatestId = UUID.randomUUID();
        ProductGlbStructureSnapshot snapshotOne = snapshot(snapshotOneId, fixture.productId(), fixture.mediaAssetId(), 1,
                "glb-structure-1", structureJson(structure), fixture.userId());
        ProductGlbStructureSnapshot snapshotLatest = snapshot(snapshotLatestId, fixture.productId(), fixture.mediaAssetId(), 3,
                "glb-structure-1", structureJson(structure), fixture.userId());
        UUID configOneId = UUID.randomUUID();
        UUID configLatestId = UUID.randomUUID();

        inRuntimeTransaction(em -> {
            ProductGlbStructureSnapshotRepository snapshots = repository(em, ProductGlbStructureSnapshotRepository.class);
            ProductMeshConfigurationRevisionRepository configurations = repository(em, ProductMeshConfigurationRevisionRepository.class);
            snapshots.save(snapshotOne);
            snapshots.save(snapshotLatest);
            configurations.save(configuration(configOneId, fixture.productId(), fixture.mediaAssetId(), snapshotOneId,
                    1, fixture.materialId(), configurationJson(7), fixture.userId()));
            configurations.save(configuration(configLatestId, fixture.productId(), fixture.mediaAssetId(), snapshotLatestId,
                    4, fixture.materialId(), configurationJson(8), fixture.userId()));
            em.flush();
            return null;
        });

        inRuntimeTransaction(em -> {
            ProductGlbStructureSnapshotRepository snapshots = repository(em, ProductGlbStructureSnapshotRepository.class);
            ProductMeshConfigurationRevisionRepository configurations = repository(em, ProductMeshConfigurationRevisionRepository.class);
            ProductGlbStructureSnapshot latestSnapshot = snapshots
                    .findFirstByProductIdAndMediaAssetIdOrderBySnapshotRevisionDesc(fixture.productId(), fixture.mediaAssetId())
                    .orElseThrow();
            assertThat(latestSnapshot.getStructureSnapshotId()).isEqualTo(snapshotLatestId);
            assertThat(latestSnapshot.getSnapshotRevision()).isEqualTo(3L);
            assertThat(latestSnapshot.readStructure()).isEqualTo(structure);
            assertThat(snapshots.findByStructureSnapshotId(snapshotOneId)).isPresent();
            assertThat(snapshots.existsByMediaAssetId(fixture.mediaAssetId())).isTrue();
            assertThat(snapshots.existsByMediaAssetId(Long.MAX_VALUE)).isFalse();

            ProductMeshConfigurationRevision latestConfiguration = configurations
                    .findFirstByProductIdAndMediaAssetIdOrderByConfigRevisionDesc(fixture.productId(), fixture.mediaAssetId())
                    .orElseThrow();
            assertThat(latestConfiguration.getConfigurationRevisionId()).isEqualTo(configLatestId);
            assertThat(latestConfiguration.getConfigRevision()).isEqualTo(4L);
            assertThat(configurations.existsByMediaAssetId(fixture.mediaAssetId())).isTrue();
            assertThat(configurations.existsByMediaAssetId(Long.MAX_VALUE)).isFalse();
            assertThat(configurations.findFirstByProductIdAndMediaAssetIdAndStructureSnapshotIdOrderByConfigRevisionDesc(
                    fixture.productId(), fixture.mediaAssetId(), snapshotOneId))
                    .get().extracting(ProductMeshConfigurationRevision::getConfigRevision).isEqualTo(1L);
            assertThat(configurations.findFirstByProductIdAndMediaAssetIdAndStructureSnapshotIdOrderByConfigRevisionDesc(
                    fixture.productId(), fixture.mediaAssetId(), UUID.randomUUID())).isEmpty();
            assertThat(latestConfiguration.getConfigurationJson().path("meshes").get(0).path("allowedColorIds").get(0).intValue())
                    .isEqualTo(8);
            return null;
        });
    }

    @Test
    void runtimeCanInsertAndReadButCannotUpdateOrDeleteImmutableHistory() {
        UUID snapshotId = UUID.randomUUID();
        UUID configurationId = UUID.randomUUID();
        inRuntimeTransaction(em -> {
            ProductGlbStructureSnapshotRepository snapshots = repository(em, ProductGlbStructureSnapshotRepository.class);
            ProductMeshConfigurationRevisionRepository configurations = repository(em, ProductMeshConfigurationRevisionRepository.class);
            snapshots.save(snapshot(snapshotId, fixture.productId(), fixture.mediaAssetId(), 1,
                    PARSER_VERSION, structureJson(structure()), fixture.userId()));
            configurations.save(configuration(configurationId, fixture.productId(), fixture.mediaAssetId(), snapshotId,
                    1, fixture.materialId(), configurationJson(5), fixture.userId()));
            em.flush();
            return null;
        });

        assertThat(runtime.queryForObject(
                "SELECT COUNT(*) FROM product_glb_structure_snapshots WHERE structure_snapshot_id=?", Integer.class, snapshotId)).isEqualTo(1);
        assertThat(runtime.queryForObject(
                "SELECT COUNT(*) FROM product_mesh_configuration_revisions WHERE configuration_revision_id=?", Integer.class, configurationId)).isEqualTo(1);
        assertSqlState("42501", () -> runtime.update(
                "UPDATE product_glb_structure_snapshots SET parser_version='changed' WHERE structure_snapshot_id=?", snapshotId));
        assertSqlState("42501", () -> runtime.update(
                "DELETE FROM product_mesh_configuration_revisions WHERE configuration_revision_id=?", configurationId));
    }

    @Test
    void newestUnsupportedOrCorruptDocumentsFailClosedWithoutFallingBack() {
        UUID oldSnapshotId = UUID.randomUUID();
        UUID unsupportedLatestId = UUID.randomUUID();
        insertSnapshot(oldSnapshotId, fixture.productId(), fixture.mediaAssetId(), 1,
                PARSER_VERSION, structureJson(structure()));
        insertSnapshot(unsupportedLatestId, fixture.productId(), fixture.mediaAssetId(), 2,
                "glb-structure-future", structureJson(structure()));

        assertThatThrownBy(() -> inRuntimeTransaction(em -> repository(em, ProductGlbStructureSnapshotRepository.class)
                .findFirstByProductIdAndMediaAssetIdOrderBySnapshotRevisionDesc(fixture.productId(), fixture.mediaAssetId())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Unsupported GLB structure parser version: glb-structure-future");

        insertSnapshot(UUID.randomUUID(), fixture.productId(), fixture.mediaAssetId(), 4,
                PARSER_VERSION, JSON.createObjectNode().put("defaultSceneIndex", 0));
        assertThatThrownBy(() -> inRuntimeTransaction(em -> repository(em, ProductGlbStructureSnapshotRepository.class)
                .findFirstByProductIdAndMediaAssetIdOrderBySnapshotRevisionDesc(fixture.productId(), fixture.mediaAssetId())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Stored GLB structure field must be an array: scenes");

        UUID configSnapshotId = UUID.randomUUID();
        insertSnapshot(configSnapshotId, fixture.productId(), fixture.mediaAssetId(), 3,
                PARSER_VERSION, structureJson(structure()));
        insertConfiguration(UUID.randomUUID(), fixture.productId(), fixture.mediaAssetId(), configSnapshotId,
                1, fixture.materialId(), configurationJson(4));
        insertConfiguration(UUID.randomUUID(), fixture.productId(), fixture.mediaAssetId(), configSnapshotId,
                2, fixture.materialId(), JSON.createObjectNode().put("schemaVersion", 99).set("meshes", JSON.createArrayNode()));
        assertThatThrownBy(() -> inRuntimeTransaction(em -> repository(em, ProductMeshConfigurationRevisionRepository.class)
                .findFirstByProductIdAndMediaAssetIdOrderByConfigRevisionDesc(fixture.productId(), fixture.mediaAssetId())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Unsupported mesh configuration JSON schema version: 99");

        insertConfiguration(UUID.randomUUID(), fixture.otherProductId(), fixture.otherMediaAssetId(),
                insertSnapshot(fixture.otherProductId(), fixture.otherMediaAssetId(), 1, PARSER_VERSION, structureJson(structure())),
                1, fixture.materialId(), JSON.createObjectNode().put("schemaVersion", 1).put("meshes", "corrupt"));
        assertThatThrownBy(() -> inRuntimeTransaction(em -> repository(em, ProductMeshConfigurationRevisionRepository.class)
                .findFirstByProductIdAndMediaAssetIdOrderByConfigRevisionDesc(fixture.otherProductId(), fixture.otherMediaAssetId())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Mesh configuration JSON meshes must be an array");
    }

    @Test
    void databaseRejectsDuplicateRevisionsAndWrongCompositeSnapshotIdentity() {
        UUID validSnapshot = UUID.randomUUID();
        insertSnapshot(validSnapshot, fixture.productId(), fixture.mediaAssetId(), 1,
                PARSER_VERSION, structureJson(structure()));
        assertSqlState("23505", () -> insertSnapshot(UUID.randomUUID(), fixture.productId(), fixture.mediaAssetId(), 1,
                PARSER_VERSION, structureJson(structure())));
        insertSnapshot(UUID.randomUUID(), fixture.otherProductId(), fixture.otherMediaAssetId(), 1,
                PARSER_VERSION, structureJson(structure()));
        insertConfiguration(UUID.randomUUID(), fixture.productId(), fixture.mediaAssetId(), validSnapshot,
                1, fixture.materialId(), configurationJson(5));
        assertSqlState("23505", () -> insertConfiguration(UUID.randomUUID(), fixture.productId(), fixture.mediaAssetId(),
                validSnapshot, 1, fixture.materialId(), configurationJson(6)));
        assertSqlState("23503", () -> insertConfiguration(UUID.randomUUID(), fixture.otherProductId(), fixture.otherMediaAssetId(),
                validSnapshot, 2, fixture.materialId(), configurationJson(6)));
    }

    private static void provisionTestRoles() {
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
        bootstrap.execute("GRANT CONNECT ON DATABASE mesh_history_persistence_test TO admin3d_migrator, app_runtime");
        bootstrap.execute("GRANT USAGE, CREATE ON SCHEMA public TO admin3d_owner");
        bootstrap.execute("GRANT USAGE, CREATE ON SCHEMA public TO admin3d_migrator WITH GRANT OPTION");
        bootstrap.execute("GRANT USAGE ON SCHEMA public TO app_runtime");
    }

    private static ProductAsset createProductAsset(int userId, String label) {
        long mediaAssetId = bootstrap.queryForObject("""
                INSERT INTO media_asset(operation_id,media_kind,provider,cloud_name,public_id,resource_type,
                    delivery_type,format,byte_count,state)
                VALUES (?, 'MODEL_3D','TEST','local','mesh-history-%s-%s','raw','private','glb',64,'ATTACHED')
                RETURNING id
                """.formatted(label, UUID.randomUUID()), Long.class, UUID.randomUUID());
        int productId = bootstrap.queryForObject("""
                INSERT INTO model_3d(source_type,user_id,name,model_status,media_asset_id)
                VALUES ('DESIGNER_MODEL', ?, ?, 'ACTIVE', ?)
                RETURNING model_id
                """, Integer.class, userId, "Mesh history " + label + " " + UUID.randomUUID(), mediaAssetId);
        return new ProductAsset(productId, mediaAssetId);
    }

    private <T> T inRuntimeTransaction(Function<EntityManager, T> work) {
        EntityManager em = entityManagerFactory.createEntityManager();
        EntityTransaction transaction = em.getTransaction();
        try {
            transaction.begin();
            T result = work.apply(em);
            em.flush();
            transaction.commit();
            return result;
        } catch (RuntimeException exception) {
            if (transaction.isActive()) transaction.rollback();
            throw exception;
        } finally {
            em.close();
        }
    }

    private static <T, ID> T repository(EntityManager em, Class<T> repositoryType) {
        return new JpaRepositoryFactory(em).getRepository(repositoryType);
    }

    private ProductGlbStructureSnapshot snapshot(UUID id, Integer productId, Long mediaAssetId, long revision,
                                                 String parserVersion, JsonNode json, int createdBy) {
        return new ProductGlbStructureSnapshot(id, productId, mediaAssetId, revision, parserVersion,
                json, Instant.now(), createdBy);
    }

    private ProductMeshConfigurationRevision configuration(UUID id, Integer productId, Long mediaAssetId,
                                                           UUID snapshotId, long revision, Integer materialId,
                                                           JsonNode json, int createdBy) {
        return new ProductMeshConfigurationRevision(id, productId, mediaAssetId, snapshotId, revision,
                materialId, json, Instant.now(), createdBy);
    }

    private void insertSnapshot(UUID id, Integer productId, Long mediaAssetId, long revision,
                                String parserVersion, JsonNode json) {
        bootstrap.update("""
                INSERT INTO product_glb_structure_snapshots
                    (structure_snapshot_id,product_id,media_asset_id,snapshot_revision,parser_version,structure_json,created_by)
                VALUES (?,?,?,?,?,?::jsonb,?)
                """, id, productId, mediaAssetId, revision, parserVersion, json.toString(), fixture.userId());
    }

    private UUID insertSnapshot(Integer productId, Long mediaAssetId, long revision,
                                String parserVersion, JsonNode json) {
        UUID id = UUID.randomUUID();
        insertSnapshot(id, productId, mediaAssetId, revision, parserVersion, json);
        return id;
    }

    private void insertConfiguration(UUID id, Integer productId, Long mediaAssetId, UUID snapshotId,
                                     long revision, Integer materialId, JsonNode json) {
        bootstrap.update("""
                INSERT INTO product_mesh_configuration_revisions
                    (configuration_revision_id,product_id,media_asset_id,structure_snapshot_id,config_revision,
                     material_id,configuration_json,created_by)
                VALUES (?,?,?,?,?,?,?::jsonb,?)
                """, id, productId, mediaAssetId, snapshotId, revision, materialId, json.toString(), fixture.userId());
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

    private static GlbStructure structure() {
        return new GlbStructure(-1,
                List.of(new GlbStructure.Scene(0, "Main", List.of(0))),
                List.of(new GlbStructure.Node(0, "Node", 0, List.of(), List.of(0))),
                List.of(new GlbStructure.Mesh(0, "Mesh", List.of(
                        new GlbStructure.Primitive(0, null, List.of("POSITION"), List.of())))),
                List.of(), List.of(), List.of(),
                List.of(new GlbStructure.PrimitiveOccurrence(0, 0, 0, "Node", null)));
    }

    private static JsonNode structureJson(GlbStructure structure) {
        return JSON.valueToTree(structure);
    }

    private static JsonNode configurationJson(int colorId) {
        return JSON.createObjectNode().put("schemaVersion", 1)
                .set("meshes", JSON.createArrayNode().add(JSON.createObjectNode()
                        .put("nodeIndex", 0).put("meshIndex", 0).put("primitiveIndex", 0)
                        .put("customizable", true).put("defaultColorId", colorId)
                        .set("allowedColorIds", JSON.createArrayNode().add(colorId))));
    }

    private record ProductAsset(int productId, long mediaAssetId) {}
    private record Fixture(int userId, int materialId, int productId, long mediaAssetId,
                           int otherProductId, long otherMediaAssetId) {}
}
