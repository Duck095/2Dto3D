package com.exe.morphia3d.repository;

import com.exe.morphia3d.Morphia3dApplication;
import com.exe.morphia3d.entity.AIGenerationSession;
import com.exe.morphia3d.entity.Cart;
import com.exe.morphia3d.entity.CartItem;
import com.exe.morphia3d.entity.Color;
import com.exe.morphia3d.entity.Feedback;
import com.exe.morphia3d.entity.Material;
import com.exe.morphia3d.entity.MaterialColor;
import com.exe.morphia3d.entity.MaterialColorId;
import com.exe.morphia3d.entity.MaterialStockLog;
import com.exe.morphia3d.entity.MediaAsset;
import com.exe.morphia3d.entity.Model3d;
import com.exe.morphia3d.entity.ModelImage;
import com.exe.morphia3d.entity.ModelPart;
import com.exe.morphia3d.entity.Order;
import com.exe.morphia3d.entity.OrderItem;
import com.exe.morphia3d.entity.Payment;
import com.exe.morphia3d.entity.PriceEstimation;
import com.exe.morphia3d.entity.Printer;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.MaterialStatus;
import com.exe.morphia3d.enums.ModelStatus;
import com.exe.morphia3d.enums.MediaAssetState;
import com.exe.morphia3d.enums.MediaKind;
import com.exe.morphia3d.enums.MediaProvider;
import com.exe.morphia3d.enums.ModelType;
import com.exe.morphia3d.enums.PaymentMethod;
import com.exe.morphia3d.service.MaterialService;
import com.exe.morphia3d.service.MediaAssetCleanupLease;
import com.exe.morphia3d.service.MediaAssetIntentSpec;
import com.exe.morphia3d.service.MediaAssetLifecycleService;
import com.exe.morphia3d.service.MediaAssetOperationLease;
import com.exe.morphia3d.service.MediaUploadIntent;
import com.exe.morphia3d.service.MediaUploadResult;
import com.exe.morphia3d.test.support.PostgresTestRoleProvisioning;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.LockModeType;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.output.MigrateResult;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.core.env.Environment;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Migration and persistence certification against the fresh V1 then V2 schema.
 * This class intentionally fails when Docker is unavailable; PostgreSQL certification must not skip.
 */
@SpringBootTest(classes = Morphia3dApplication.class, properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "spring.flyway.enabled=false",
        "spring.flyway.baseline-on-migrate=false",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "media.lifecycle.reconciliation-interval-millis=3600000"
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = false)
class PostgresV1V2SchemaIntegrationTest {

    private static final Duration LOCK_OBSERVATION_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration ROW_LOCK_HOLD_TIMEOUT = Duration.ofSeconds(30);
    private static final long WORKER_COMPLETION_TIMEOUT_SECONDS = 15;
    private static final long EXECUTOR_TERMINATION_TIMEOUT_SECONDS = 30;
    private static final String DATABASE_LOCK_TIMEOUT = "20s";
    private static final String DATABASE_STATEMENT_TIMEOUT = "25s";

    private static final String MIGRATION_USER = "schema_migrator";
    private static final String MIGRATION_PASSWORD = "migration_test_only";
    private static final String APPLICATION_USER = "schema_application";
    private static final String APPLICATION_PASSWORD = "application_test_only";
    private static final String LEGACY_MODEL_PATH = "legacy/models/v1-chair.glb";
    private static final String LEGACY_IMAGE_PATH = "legacy/images/v1-chair-cover";
    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse(
            "postgres@sha256:721873c34ceb9f8d8fc265984940dc982404c105f19ad51be9fdc5970a6080ea")
            .asCompatibleSubstituteFor("postgres");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE)
            .withDatabaseName("morphia_v1_v2")
            .withUsername(MIGRATION_USER)
            .withPassword(MIGRATION_PASSWORD);

    private static boolean schemaPrepared;
    private static int v1MigrationsExecuted;
    private static int v2MigrationsExecuted;
    private static int v3MigrationsExecuted;
    private static int repeatedMigrationsExecuted;
    private static int currentMigrationsExecuted;
    private static int postV14MigrationsExecuted;
    private static List<String> historicalMigrationVersions;
    private static List<String> currentMigrationVersions;
    private static int baselineRows;
    private static Integer legacyModelId;
    private static Integer legacyImageId;
    private static String migratedSchemaFingerprint;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private MaterialService materialService;

    @Autowired
    private Environment environment;

    @Autowired
    private MediaAssetLifecycleService mediaAssetLifecycle;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        prepareFreshMigratedDatabase();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APPLICATION_USER);
        registry.add("spring.datasource.password", () -> APPLICATION_PASSWORD);
    }

    private static synchronized void prepareFreshMigratedDatabase() {
        if (schemaPrepared) {
            return;
        }

        if (!POSTGRES.isRunning()) {
            throw new IllegalStateException("Testcontainers must start PostgreSQL before Spring context setup");
        }
        DataSource migrationDataSource = migrationDataSource();
        JdbcTemplate migrationJdbc = new JdbcTemplate(migrationDataSource);
        PostgresTestRoleProvisioning.provisionMigrationRoles(migrationJdbc, MIGRATION_USER);
        Flyway throughV1 = flyway(migrationDataSource)
                .target(MigrationVersion.fromVersion("1"))
                .load();
        MigrateResult v1Result = throughV1.migrate();
        v1MigrationsExecuted = v1Result.migrationsExecuted;

        legacyModelId = migrationJdbc.queryForObject(
                "INSERT INTO model_3d (source_type, file_path_3d) " +
                        "VALUES ('DESIGNER_MODEL', ?) RETURNING model_id",
                Integer.class, LEGACY_MODEL_PATH);
        legacyImageId = migrationJdbc.queryForObject(
                "INSERT INTO model_images (model_id, image_path, is_primary) " +
                        "VALUES (?, ?, TRUE) RETURNING image_id",
                Integer.class, legacyModelId, LEGACY_IMAGE_PATH);

        Flyway throughV2 = flyway(migrationDataSource).target(MigrationVersion.fromVersion("2")).load();
        v2MigrationsExecuted = throughV2.migrate().migrationsExecuted;
        throughV2.validate();
        Flyway throughV3 = flyway(migrationDataSource).target(MigrationVersion.fromVersion("3")).load();
        v3MigrationsExecuted = throughV3.migrate().migrationsExecuted;
        throughV3.validate();
        historicalMigrationVersions = migrationJdbc.queryForList(
                "SELECT version FROM flyway_schema_history ORDER BY installed_rank", String.class);
        repeatedMigrationsExecuted = throughV3.migrate().migrationsExecuted;
        throughV3.validate();

        Flyway throughV14 = flyway(migrationDataSource).target(MigrationVersion.fromVersion("14")).load();
        currentMigrationsExecuted = throughV14.migrate().migrationsExecuted;
        PostgresTestRoleProvisioning.grantSnapshotReferencePrivileges(migrationJdbc);
        Flyway currentSchema = flyway(migrationDataSource).load();
        postV14MigrationsExecuted = currentSchema.migrate().migrationsExecuted;
        currentSchema.validate();
        currentMigrationVersions = migrationJdbc.queryForList(
                "SELECT version FROM flyway_schema_history ORDER BY installed_rank", String.class);
        baselineRows = migrationJdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE type = 'BASELINE'", Integer.class);

        createLeastPrivilegeApplicationRole(migrationJdbc);
        migratedSchemaFingerprint = schemaFingerprint(migrationDataSource);
        schemaPrepared = true;
    }

    private static FluentConfiguration flyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .schemas("public")
                .defaultSchema("public")
                .baselineOnMigrate(false);
    }

    private static DataSource migrationDataSource() {
        return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), MIGRATION_USER, MIGRATION_PASSWORD);
    }

    private static void createLeastPrivilegeApplicationRole(JdbcTemplate migrationJdbc) {
        migrationJdbc.execute("CREATE ROLE " + APPLICATION_USER + " LOGIN PASSWORD '" + APPLICATION_PASSWORD + "'");
        migrationJdbc.execute("GRANT CONNECT ON DATABASE morphia_v1_v2 TO " + APPLICATION_USER);
        migrationJdbc.execute("GRANT USAGE ON SCHEMA public TO " + APPLICATION_USER);
        migrationJdbc.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO " +
                APPLICATION_USER);
        migrationJdbc.execute("GRANT SELECT ON TABLE flyway_schema_history TO " + APPLICATION_USER);
        migrationJdbc.execute("REVOKE INSERT, UPDATE, DELETE ON TABLE flyway_schema_history FROM " +
                APPLICATION_USER);
        migrationJdbc.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO " + APPLICATION_USER);
    }

    private static String schemaFingerprint(DataSource dataSource) {
        JdbcTemplate schemaJdbc = new JdbcTemplate(dataSource);
        List<String> schemaEntries = new ArrayList<>();
        schemaEntries.addAll(schemaJdbc.queryForList("""
                SELECT concat_ws('|', 'COLUMN', table_name, column_name, ordinal_position::text,
                    data_type, is_nullable, coalesce(column_default, ''), is_identity,
                    coalesce(identity_generation, ''))
                FROM information_schema.columns
                WHERE table_schema = 'public'
                ORDER BY table_name, ordinal_position
                """, String.class));
        schemaEntries.addAll(schemaJdbc.queryForList("""
                SELECT concat_ws('|', 'CONSTRAINT', relation.relname, constraint_row.conname,
                    constraint_row.contype::text, pg_get_constraintdef(constraint_row.oid, true))
                FROM pg_constraint constraint_row
                JOIN pg_class relation ON relation.oid = constraint_row.conrelid
                JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
                WHERE schema_row.nspname = 'public'
                ORDER BY relation.relname, constraint_row.conname
                """, String.class));
        schemaEntries.addAll(schemaJdbc.queryForList("""
                SELECT concat_ws('|', 'INDEX', tablename, indexname, indexdef)
                FROM pg_indexes
                WHERE schemaname = 'public'
                ORDER BY tablename, indexname
                """, String.class));
        schemaEntries.addAll(schemaJdbc.queryForList("""
                SELECT concat_ws('|', 'SEQUENCE', sequence_row.relname,
                    sequence_definition.seqstart::text, sequence_definition.seqincrement::text,
                    sequence_definition.seqmin::text, sequence_definition.seqmax::text,
                    sequence_definition.seqcache::text, sequence_definition.seqcycle::text,
                    coalesce(owner_table.relname, ''), coalesce(owner_column.attname, ''))
                FROM pg_class sequence_row
                JOIN pg_namespace schema_row ON schema_row.oid = sequence_row.relnamespace
                JOIN pg_sequence sequence_definition ON sequence_definition.seqrelid = sequence_row.oid
                LEFT JOIN pg_depend ownership ON ownership.objid = sequence_row.oid
                    AND ownership.deptype IN ('a', 'i')
                LEFT JOIN pg_class owner_table ON owner_table.oid = ownership.refobjid
                LEFT JOIN pg_attribute owner_column ON owner_column.attrelid = owner_table.oid
                    AND owner_column.attnum = ownership.refobjsubid
                WHERE schema_row.nspname = 'public' AND sequence_row.relkind = 'S'
                ORDER BY sequence_row.relname
                """, String.class));
        schemaEntries.sort(String::compareTo);

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\n", schemaEntries).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    @Test
    void freshDatabaseRunsV1ThenV2ThenV3WithoutBaselineAndValidatesFlywayHistory() {
        assertThat(v1MigrationsExecuted).isEqualTo(1);
        assertThat(v2MigrationsExecuted).isEqualTo(1);
        assertThat(v3MigrationsExecuted).isEqualTo(1);
        assertThat(repeatedMigrationsExecuted).isZero();
        assertThat(historicalMigrationVersions).containsExactly("1", "2", "3");
        assertThat(currentMigrationsExecuted).isEqualTo(11);
        assertThat(postV14MigrationsExecuted).isEqualTo(6);
        assertThat(currentMigrationVersions).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19", "20");
        assertThat(baselineRows).isZero();

        List<Map<String, Object>> history = jdbc.queryForList(
                "SELECT version, type, success FROM flyway_schema_history ORDER BY installed_rank");
        assertThat(history).extracting(row -> row.get("version"))
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19", "20");
        assertThat(history).allSatisfy(row -> {
            assertThat(row.get("type")).isEqualTo("SQL");
            assertThat(row.get("success")).isEqualTo(true);
        });
    }

    @Test
    void v2PreservesLegacyPathsAndLeavesNewMediaReferencesNull() {
        assertThat(jdbc.queryForObject(
                "SELECT file_path_3d FROM model_3d WHERE model_id = ?", String.class, legacyModelId))
                .isEqualTo(LEGACY_MODEL_PATH);
        assertThat(jdbc.queryForObject(
                "SELECT media_asset_id IS NULL FROM model_3d WHERE model_id = ?", Boolean.class, legacyModelId))
                .isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT image_path FROM model_images WHERE image_id = ?", String.class, legacyImageId))
                .isEqualTo(LEGACY_IMAGE_PATH);
        assertThat(jdbc.queryForObject(
                "SELECT media_asset_id IS NULL FROM model_images WHERE image_id = ?", Boolean.class, legacyImageId))
                .isTrue();
    }

    @Test
    void actualApplicationStartsWithValidateAndDoesNotModifyMigratedSchema() {
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(environment.getProperty("spring.jpa.database-platform"))
                .isEqualTo("org.hibernate.dialect.PostgreSQLDialect");
        assertThat(environment.getProperty("spring.flyway.enabled", Boolean.class)).isFalse();
        SessionFactoryImplementor sessionFactory = entityManagerFactory.unwrap(SessionFactoryImplementor.class);
        assertThat(sessionFactory.getJdbcServices().getDialect()).isInstanceOf(PostgreSQLDialect.class);
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APPLICATION_USER);
        assertThat(jdbc.queryForObject("SELECT has_schema_privilege(current_user, 'public', 'CREATE')", Boolean.class))
                .isFalse();
        assertThat(jdbc.queryForObject("""
                SELECT has_table_privilege(current_user, 'public.flyway_schema_history', 'SELECT')
                """, Boolean.class)).isTrue();
        for (String privilege : List.of("INSERT", "UPDATE", "DELETE")) {
            assertThat(jdbc.queryForObject(
                    "SELECT has_table_privilege(current_user, 'public.flyway_schema_history', ?)",
                    Boolean.class, privilege)).isFalse();
        }
        assertThat(jdbc.queryForObject(
                "SELECT has_table_privilege(current_user, 'public.materials', 'INSERT')", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT has_table_privilege(current_user, 'public.materials', 'UPDATE')", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT has_sequence_privilege(current_user,
                    pg_get_serial_sequence('public.materials', 'material_id'), 'USAGE')
                """, Boolean.class)).isTrue();
        assertThat(schemaFingerprint(migrationDataSource())).isEqualTo(migratedSchemaFingerprint);
    }

    @Test
    @Transactional
    void allIdentityEntitiesAndMaterialColorCompositeKeyPersistAgainstMigratedSchema() {
        User user = User.builder()
                .name("Schema verification user")
                .email("schema-" + UUID.randomUUID() + "@example.test")
                .password("test-only")
                .build();
        entityManager.persist(user);

        Printer printer = Printer.builder().name("Schema verification printer").build();
        entityManager.persist(printer);
        Color color = Color.builder().name("Schema color " + UUID.randomUUID()).hexCode("#123456").build();
        entityManager.persist(color);
        Material material = Material.builder()
                .name("Schema verification material")
                .pricePerGram(0.75)
                .stockQuantity(30.0)
                .unit("g")
                .isAvailable(MaterialStatus.AVAILABLE)
                .build();
        entityManager.persist(material);

        MaterialColor materialColor = MaterialColor.builder()
                .id(new MaterialColorId())
                .material(material)
                .color(color)
                .quantity(12.0)
                .build();
        entityManager.persist(materialColor);

        AIGenerationSession session = AIGenerationSession.builder()
                .user(user)
                .originalImagePath("legacy/ai/input")
                .prompt("schema smoke")
                .build();
        entityManager.persist(session);
        MediaAsset modelAsset = mediaAsset(MediaKind.MODEL_3D, "products/models/schema-" + UUID.randomUUID() + ".glb");
        MediaAsset imageAsset = mediaAsset(MediaKind.IMAGE, "products/images/schema-" + UUID.randomUUID());
        entityManager.persist(modelAsset);
        entityManager.persist(imageAsset);

        Model3d model = Model3d.builder()
                .sourceType(ModelType.DESIGNER_MODEL)
                .user(user)
                .generationSession(session)
                .name("Schema verification model")
                .filePath3d(modelAsset.getPublicId())
                .mediaAsset(modelAsset)
                .build();
        entityManager.persist(model);

        ModelImage image = ModelImage.builder()
                .model(model)
                .imagePath(imageAsset.getPublicId())
                .mediaAsset(imageAsset)
                .isPrimary(true)
                .build();
        entityManager.persist(image);
        ModelPart part = ModelPart.builder()
                .model(model)
                .partName("Body")
                .partKey("body")
                .color(color)
                .build();
        entityManager.persist(part);

        MaterialStockLog stockLog = new MaterialStockLog();
        stockLog.setMaterial(material);
        stockLog.setCreatedBy(user);
        stockLog.setChangeType("RECEIVED");
        stockLog.setQuantity(30.0);
        entityManager.persist(stockLog);

        PriceEstimation estimate = new PriceEstimation();
        estimate.setModel(model);
        estimate.setMaterial(material);
        estimate.setPrinter(printer);
        estimate.setTotalPrice(18.0);
        entityManager.persist(estimate);

        Order order = Order.builder()
                .user(user)
                .printer(printer)
                .totalAmount(18.0)
                .build();
        entityManager.persist(order);
        OrderItem orderItem = OrderItem.builder()
                .order(order)
                .model(model)
                .estimate(estimate)
                .quantity(1)
                .priceSnapshot(18.0)
                .build();
        entityManager.persist(orderItem);
        Cart cart = new Cart();
        cart.setUser(user);
        entityManager.persist(cart);
        CartItem cartItem = new CartItem();
        cartItem.setCart(cart);
        cartItem.setModel(model);
        cartItem.setEstimate(estimate);
        cartItem.setQuantity(1);
        entityManager.persist(cartItem);
        Payment payment = Payment.builder()
                .order(order)
                .amount(18.0)
                .method(PaymentMethod.VNPAY)
                .build();
        entityManager.persist(payment);
        Feedback feedback = new Feedback();
        feedback.setUser(user);
        feedback.setOrderItem(orderItem);
        feedback.setRating(5);
        entityManager.persist(feedback);

        entityManager.flush();

        assertThat(Arrays.asList(user.getId(), printer.getId(), color.getId(), material.getId(), session.getId(),
                modelAsset.getId(), imageAsset.getId(), model.getId(), image.getId(), part.getId(), stockLog.getId(),
                estimate.getId(), order.getId(), orderItem.getId(), cart.getId(), cartItem.getId(), payment.getId(),
                feedback.getId())).doesNotContainNull();
        assertThat(materialColor.getId().getMaterialId()).isEqualTo(material.getId());
        assertThat(materialColor.getId().getColorId()).isEqualTo(color.getId());
        entityManager.clear();
        MaterialColor reloadedColor = entityManager.find(MaterialColor.class,
                new MaterialColorId(material.getId(), color.getId()));
        assertThat(reloadedColor.getQuantity()).isEqualTo(12.0);
    }

    @Test
    @Transactional
    void materialRetirementPreservesStockLogColorsAndHistoricalEstimateReferences() {
        User author = User.builder()
                .name("Stock auditor")
                .email("retire-" + UUID.randomUUID() + "@example.test")
                .password("test-only")
                .build();
        entityManager.persist(author);
        Color color = Color.builder().name("RetColor-" + UUID.randomUUID()).build();
        entityManager.persist(color);
        Material material = Material.builder()
                .name("Retirement material")
                .pricePerGram(1.0)
                .stockQuantity(50.0)
                .unit("g")
                .isAvailable(MaterialStatus.AVAILABLE)
                .build();
        entityManager.persist(material);
        MaterialColor materialColor = MaterialColor.builder()
                .id(new MaterialColorId())
                .material(material)
                .color(color)
                .quantity(25.0)
                .build();
        entityManager.persist(materialColor);
        MaterialStockLog stockLog = new MaterialStockLog();
        stockLog.setMaterial(material);
        stockLog.setCreatedBy(author);
        stockLog.setChangeType("RECEIVED");
        stockLog.setQuantity(50.0);
        stockLog.setNote("Retain this audit entry");
        entityManager.persist(stockLog);
        Model3d model = Model3d.builder().sourceType(ModelType.DESIGNER_MODEL).name("Historical estimate model").build();
        entityManager.persist(model);
        Printer printer = Printer.builder().name("Historical estimate printer").build();
        entityManager.persist(printer);
        PriceEstimation estimate = new PriceEstimation();
        estimate.setModel(model);
        estimate.setMaterial(material);
        estimate.setPrinter(printer);
        estimate.setTotalPrice(12.0);
        entityManager.persist(estimate);
        entityManager.flush();

        Integer materialId = material.getId();
        Integer stockLogId = stockLog.getId();
        Integer colorId = color.getId();
        Integer estimateId = estimate.getId();
        materialService.removeMaterial(materialId);
        entityManager.flush();
        entityManager.clear();

        Material retired = entityManager.find(Material.class, materialId);
        MaterialStockLog retainedLog = entityManager.find(MaterialStockLog.class, stockLogId);
        MaterialColor retainedColor = entityManager.find(MaterialColor.class, new MaterialColorId(materialId, colorId));
        PriceEstimation retainedEstimate = entityManager.find(PriceEstimation.class, estimateId);

        assertThat(retired.getIsAvailable()).isEqualTo(MaterialStatus.UNAVAILABLE);
        assertThat(retired.getStockQuantity()).isEqualTo(50.0);
        assertThat(retainedLog.getMaterial().getId()).isEqualTo(materialId);
        assertThat(retainedLog.getCreatedBy().getId()).isEqualTo(author.getId());
        assertThat(retainedLog.getChangeType()).isEqualTo("RECEIVED");
        assertThat(retainedLog.getQuantity()).isEqualTo(50.0);
        assertThat(retainedLog.getNote()).isEqualTo("Retain this audit entry");
        assertThat(retainedColor.getQuantity()).isEqualTo(25.0);
        assertThat(retainedColor.getColor().getId()).isEqualTo(colorId);
        assertThat(retainedEstimate.getMaterial().getId()).isEqualTo(materialId);
        assertThat(retainedEstimate.getTotalPrice()).isEqualTo(12.0);
    }

    @Test
    void competingCleanupClaimsOverlapAndOnlyOneWorkerAcquiresTheLease() throws Exception {
        ReadyMediaFixture fixture = createReadyModelAsset();
        long assetId = fixture.operation().mediaAssetIds().get(0);
        mediaAssetLifecycle.requestCleanup(fixture.operation(), fixture.operation().mediaAssetIds());
        makeCleanupEligible(assetId);

        ExecutorService workers = Executors.newFixedThreadPool(3);
        CountDownLatch blockerLocked = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        CountDownLatch claimersReady = new CountDownLatch(2);
        CountDownLatch startClaimers = new CountDownLatch(1);
        try {
            Future<?> blocker = holdOperationRowLock(fixture.operation().operationId(), workers,
                    blockerLocked, releaseBlocker);
            awaitLatch(blockerLocked, "row-lock blocker to acquire the operation lock");

            Callable<MediaAssetCleanupLease> firstClaim = () -> {
                claimersReady.countDown();
                awaitLatch(startClaimers, "cleanup claim start signal");
                return transactionTemplate().execute(status -> {
                    configureWorkerDatabaseSession("media-cleanup-claim-a");
                    return mediaAssetLifecycle.claimCleanup(assetId, "postgres-worker-a");
                });
            };
            Callable<MediaAssetCleanupLease> secondClaim = () -> {
                claimersReady.countDown();
                awaitLatch(startClaimers, "cleanup claim start signal");
                return transactionTemplate().execute(status -> {
                    configureWorkerDatabaseSession("media-cleanup-claim-b");
                    return mediaAssetLifecycle.claimCleanup(assetId, "postgres-worker-b");
                });
            };
            Future<MediaAssetCleanupLease> first = workers.submit(firstClaim);
            Future<MediaAssetCleanupLease> second = workers.submit(secondClaim);
            awaitLatch(claimersReady, "both cleanup workers to reach the start gate");
            startClaimers.countDown();

            awaitDatabaseLockWaiters("media-cleanup-claim-a", "media-cleanup-claim-b");
            releaseBlocker.countDown();
            blocker.get(WORKER_COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            MediaAssetCleanupLease firstLease = first.get(WORKER_COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            MediaAssetCleanupLease secondLease = second.get(WORKER_COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat((firstLease == null) ^ (secondLease == null)).isTrue();
            MediaAssetCleanupLease winner = firstLease == null ? secondLease : firstLease;
            assertThat(jdbc.queryForObject("SELECT state FROM media_asset WHERE id = ?", String.class, assetId))
                    .isEqualTo(MediaAssetState.CLEANUP_LEASED.name());
            assertThat(jdbc.queryForObject("SELECT lease_token FROM media_asset WHERE id = ?", UUID.class, assetId))
                    .isEqualTo(winner.leaseToken());

            MediaAssetCleanupLease staleToken = new MediaAssetCleanupLease(assetId, fixture.operation().operationId(),
                    UUID.randomUUID());
            assertThatThrownBy(() -> mediaAssetLifecycle.verifyCleanupLease(staleToken))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(jdbc.queryForObject("SELECT state FROM media_asset WHERE id = ?", String.class, assetId))
                    .isEqualTo(MediaAssetState.CLEANUP_LEASED.name());
        } finally {
            releaseBlocker.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(EXECUTOR_TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    // This overlaps attachment with requestCleanup(), not cleanup-provider deletion. It accepts either lock winner;
    // deterministic attachment-first and cleanup-first interleavings remain follow-up coverage.
    void attachmentAndCleanupRequestRaceSerializeWithoutLosingOrDeletingAReference() throws Exception {
        ReadyMediaFixture fixture = createReadyModelAsset();
        long assetId = fixture.operation().mediaAssetIds().get(0);
        ExecutorService workers = Executors.newFixedThreadPool(3);
        CountDownLatch blockerLocked = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        CountDownLatch contendersReady = new CountDownLatch(2);
        CountDownLatch startContenders = new CountDownLatch(1);
        try {
            Future<?> blocker = holdOperationRowLock(fixture.operation().operationId(), workers,
                    blockerLocked, releaseBlocker);
            awaitLatch(blockerLocked, "row-lock blocker to acquire the operation lock");

            Future<RaceOutcome> attachment = workers.submit(() -> {
                contendersReady.countDown();
                awaitLatch(startContenders, "attachment race start signal");
                try {
                    persistModelReferenceAndAttach(fixture.operation(), assetId, "media-attachment-race");
                    return RaceOutcome.ATTACHED;
                } catch (IllegalStateException rejectedByCleanup) {
                    return RaceOutcome.REJECTED;
                }
            });
            Future<RaceOutcome> cleanup = workers.submit(() -> {
                contendersReady.countDown();
                awaitLatch(startContenders, "cleanup race start signal");
                try {
                    int changed = transactionTemplate().execute(status -> {
                        configureWorkerDatabaseSession("media-cleanup-race");
                        return mediaAssetLifecycle.requestCleanup(fixture.operation(),
                                fixture.operation().mediaAssetIds());
                    });
                    return changed == 1 ? RaceOutcome.CLEANUP_REQUESTED : RaceOutcome.REJECTED;
                } catch (IllegalStateException rejectedByAttachment) {
                    return RaceOutcome.REJECTED;
                }
            });
            awaitLatch(contendersReady, "attachment and cleanup contenders to reach the start gate");
            startContenders.countDown();

            awaitDatabaseLockWaiters("media-attachment-race", "media-cleanup-race");
            releaseBlocker.countDown();
            blocker.get(WORKER_COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            RaceOutcome attachmentResult = attachment.get(WORKER_COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            RaceOutcome cleanupResult = cleanup.get(WORKER_COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat((attachmentResult == RaceOutcome.ATTACHED)
                    ^ (cleanupResult == RaceOutcome.CLEANUP_REQUESTED)).isTrue();

            String finalState = jdbc.queryForObject("SELECT state FROM media_asset WHERE id = ?", String.class, assetId);
            int referenceCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM model_3d WHERE media_asset_id = ?", Integer.class, assetId);
            if (attachmentResult == RaceOutcome.ATTACHED) {
                assertThat(finalState).isEqualTo(MediaAssetState.ATTACHED.name());
                assertThat(referenceCount).isEqualTo(1);
            } else {
                assertThat(finalState).isEqualTo(MediaAssetState.CLEANUP_PENDING.name());
                assertThat(referenceCount).isZero();
            }
            assertThat(finalState).isNotEqualTo(MediaAssetState.DELETED.name());
        } finally {
            releaseBlocker.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(EXECUTOR_TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void attachedAndReferencedMediaCannotBeClaimedForCleanup() {
        ReadyMediaFixture fixture = createReadyModelAsset();
        long assetId = fixture.operation().mediaAssetIds().get(0);
        persistModelReferenceAndAttach(fixture.operation(), assetId);

        assertThat(mediaAssetLifecycle.claimCleanup(assetId, "attached-media-cleaner")).isNull();
        assertThat(jdbc.queryForObject("SELECT state FROM media_asset WHERE id = ?", String.class, assetId))
                .isEqualTo(MediaAssetState.ATTACHED.name());

        // Construct a conservative recovery edge case: a referenced asset is found in cleanup-pending state.
        makeCleanupPendingAndEligible(assetId);
        assertThat(mediaAssetLifecycle.claimCleanup(assetId, "referenced-media-cleaner")).isNull();
        assertThat(jdbc.queryForObject("SELECT state FROM media_asset WHERE id = ?", String.class, assetId))
                .isEqualTo(MediaAssetState.CLEANUP_PENDING.name());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM model_3d WHERE media_asset_id = ?", Integer.class, assetId)).isEqualTo(1);
    }

    @Test
    void attachmentRollbackRestoresReadyStateAndRemovesTheReferenceAtomically() {
        ReadyMediaFixture fixture = createReadyModelAsset();
        long assetId = fixture.operation().mediaAssetIds().get(0);

        assertThatThrownBy(() -> transactionTemplate().executeWithoutResult(status -> {
            configureWorkerDatabaseSession("media-attachment-rollback");
            persistModelReference(fixture.operation(), assetId);
            mediaAssetLifecycle.attach(fixture.operation(), fixture.operation().mediaAssetIds());
            throw new IllegalStateException("force PostgreSQL attachment rollback");
        })).isInstanceOf(IllegalStateException.class).hasMessage("force PostgreSQL attachment rollback");

        assertThat(jdbc.queryForObject("SELECT state FROM media_asset WHERE id = ?", String.class, assetId))
                .isEqualTo(MediaAssetState.READY.name());
        assertThat(jdbc.queryForObject("SELECT lease_token FROM media_asset WHERE id = ?", UUID.class, assetId))
                .isEqualTo(fixture.operation().leaseToken());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM model_3d WHERE media_asset_id = ?", Integer.class, assetId)).isZero();
    }

    @Test
    void stockHistoryForeignKeyRejectsPhysicalMaterialDeletion() {
        JdbcTemplate migrationJdbc = new JdbcTemplate(migrationDataSource());
        Integer authorId = migrationJdbc.queryForObject(
                "INSERT INTO users (name, email, password) VALUES (?, ?, ?) RETURNING user_id",
                Integer.class, "Physical delete test", "fk-" + UUID.randomUUID() + "@example.test", "test-only");
        Integer materialId = migrationJdbc.queryForObject(
                "INSERT INTO materials (name, price_per_gram) VALUES (?, ?) RETURNING material_id",
                Integer.class, "Restrict test material", 1.0);
        migrationJdbc.update("INSERT INTO material_stock_logs (material_id, change_type, quantity, created_by) " +
                "VALUES (?, 'RECEIVED', 1, ?)", materialId, authorId);

        JdbcTemplate applicationJdbc = jdbc;
        assertThatThrownBy(() -> applicationJdbc.update(
                "DELETE FROM materials WHERE material_id = ?", materialId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void migratedSchemaContainsExpectedUniqueForeignKeyAndCheckConstraints() {
        List<String> constraints = jdbc.queryForList("""
                SELECT conname FROM pg_constraint constraint_row
                JOIN pg_class relation ON relation.oid = constraint_row.conrelid
                JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
                WHERE schema_row.nspname = 'public'
                """, String.class);
        assertThat(constraints).contains(
                "uq_users_email",
                "uq_colors_name",
                "uq_cart_user",
                "uq_payments_order",
                "uq_feedback_order_item",
                "ck_users_role",
                "ck_users_status",
                "ck_printers_status",
                "ck_materials_is_available",
                "ck_model_3d_source_type",
                "ck_model_3d_model_status",
                "ck_orders_status",
                "ck_payments_method",
                "ck_payments_status",
                "ck_media_asset_kind",
                "ck_media_asset_state",
                "ck_media_asset_byte_count",
                "ck_media_asset_retry_count",
                "fk_cart_user",
                "fk_ai_generation_sessions_user",
                "fk_model_3d_user",
                "fk_model_3d_generation_session",
                "fk_model_images_model",
                "fk_model_parts_model",
                "fk_model_parts_color",
                "fk_material_colors_material",
                "fk_material_colors_color",
                "fk_material_stock_logs_material",
                "fk_material_stock_logs_created_by",
                "fk_price_estimations_model",
                "fk_price_estimations_material",
                "fk_price_estimations_printer",
                "fk_orders_user",
                "fk_orders_printer",
                "fk_order_items_order",
                "fk_order_items_model",
                "fk_order_items_estimate",
                "fk_cart_items_cart",
                "fk_cart_items_model",
                "fk_cart_items_estimate",
                "fk_payments_order",
                "fk_feedback_user",
                "fk_feedback_order_item",
                "fk_model_3d_media_asset",
                "fk_model_images_media_asset",
                "uq_media_asset_provider_public_identity",
                "uq_media_asset_provider_asset_identity");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM pg_constraint constraint_row
                JOIN pg_class relation ON relation.oid = constraint_row.conrelid
                JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
                WHERE schema_row.nspname = 'public'
                  AND constraint_row.contype = 'f' AND constraint_row.confdeltype = 'c'
                """, Integer.class)).isZero();

        Map<String, Object> stockFk = jdbc.queryForMap("""
                SELECT pg_get_constraintdef(oid, true) AS definition
                FROM pg_constraint
                WHERE conname = 'fk_material_stock_logs_material'
                """);
        assertThat(stockFk.get("definition").toString()).contains("ON DELETE RESTRICT");

        List<String> mediaReferenceIndexes = jdbc.queryForList("""
                SELECT indexname FROM pg_indexes
                WHERE schemaname = 'public'
                  AND indexname IN ('uq_model_3d_media_asset', 'uq_model_images_media_asset')
                """, String.class);
        assertThat(mediaReferenceIndexes).containsExactlyInAnyOrder(
                "uq_model_3d_media_asset", "uq_model_images_media_asset");
    }

    @Test
    void caseSensitiveEmailUniquenessAndEnumChecksAreEnforced() {
        String email = "unique-" + UUID.randomUUID() + "@example.test";
        jdbc.update("INSERT INTO users (name, email, password) VALUES (?, ?, ?)", "Unique test", email, "test-only");
        jdbc.update("INSERT INTO users (name, email, password) VALUES (?, ?, ?)",
                "Case-sensitive test", email.toUpperCase(java.util.Locale.ROOT), "test-only");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email IN (?, ?)", Integer.class,
                email, email.toUpperCase(java.util.Locale.ROOT))).isEqualTo(2);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO users (name, email, password) VALUES (?, ?, ?)", "Duplicate test", email, "test-only"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO materials (name, price_per_gram, is_available)
                VALUES ('Invalid enum test', 1, 'RETIRED')
                """))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void mediaProviderPublicIdentityUniquenessIsEnforced() {
        UUID operationId = UUID.randomUUID();
        String publicId = "products/images/unique-" + UUID.randomUUID();
        insertMediaAsset(operationId, publicId, UUID.randomUUID().toString());

        assertThatThrownBy(() -> insertMediaAsset(UUID.randomUUID(), publicId, UUID.randomUUID().toString()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void mediaProviderAssetIdentityUniquenessIsEnforced() {
        String providerAssetId = UUID.randomUUID().toString();
        insertMediaAsset(UUID.randomUUID(), "products/images/provider-a-" + UUID.randomUUID(), providerAssetId);

        assertThatThrownBy(() -> insertMediaAsset(
                UUID.randomUUID(), "products/images/provider-b-" + UUID.randomUUID(), providerAssetId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private ReadyMediaFixture createReadyModelAsset() {
        UUID operationId = UUID.randomUUID();
        MediaAssetOperationLease operation = mediaAssetLifecycle.createUploadIntents(operationId,
                List.of(new MediaAssetIntentSpec(MediaKind.MODEL_3D, "glb", 128L)));
        long assetId = operation.mediaAssetIds().get(0);
        MediaUploadIntent intent = mediaAssetLifecycle.claimUpload(operation, assetId);
        assertThat(intent).isNotNull();
        MediaUploadResult result = new MediaUploadResult(
                MediaProvider.CLOUDINARY,
                "test-cloud",
                "postgres-test-asset-" + assetId,
                intent.publicId(),
                MediaKind.MODEL_3D,
                "raw",
                "upload",
                "glb",
                128L,
                1L,
                "https://res.cloudinary.com/test-cloud/raw/upload/v1/" + intent.publicId());
        mediaAssetLifecycle.recordUpload(intent, result);
        return new ReadyMediaFixture(operation);
    }

    private void makeCleanupEligible(long assetId) {
        OffsetDateTime expiredAt = Instant.now().minusSeconds(30).atOffset(ZoneOffset.UTC);
        jdbc.update("UPDATE media_asset SET lease_until = ?, updated_at = ? WHERE id = ?",
                expiredAt, expiredAt, assetId);
    }

    private void makeCleanupPendingAndEligible(long assetId) {
        OffsetDateTime expiredAt = Instant.now().minusSeconds(30).atOffset(ZoneOffset.UTC);
        jdbc.update("UPDATE media_asset SET state = ?, lease_owner = NULL, lease_token = NULL, " +
                        "lease_until = ?, updated_at = ? WHERE id = ?",
                MediaAssetState.CLEANUP_PENDING.name(), expiredAt, expiredAt, assetId);
    }

    private TransactionTemplate transactionTemplate() {
        return new TransactionTemplate(transactionManager);
    }

    private Future<?> holdOperationRowLock(UUID operationId, ExecutorService workers,
                                            CountDownLatch lockAcquired, CountDownLatch releaseLock) {
        return workers.submit(() -> transactionTemplate().executeWithoutResult(status -> {
            configureWorkerDatabaseSession("media-lock-blocker");
            entityManager.createQuery("select asset from MediaAsset asset where asset.operationId = :operationId " +
                            "order by asset.id", MediaAsset.class)
                    .setParameter("operationId", operationId)
                    .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                    .getResultList();
            lockAcquired.countDown();
            awaitLatch(releaseLock, "row-lock release signal", ROW_LOCK_HOLD_TIMEOUT);
        }));
    }

    private void awaitDatabaseLockWaiters(String... applicationNames) throws InterruptedException {
        if (applicationNames.length == 0 || Arrays.stream(applicationNames).distinct().count() != applicationNames.length) {
            throw new IllegalArgumentException("Unique contender application names are required");
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(applicationNames.length, "?"));
        String query = "SELECT COUNT(DISTINCT application_name), COUNT(DISTINCT pid) FROM pg_stat_activity " +
                "WHERE datname = current_database() AND pid <> pg_backend_pid() " +
                "AND wait_event_type = 'Lock' AND application_name IN (" + placeholders + ")";
        // Observe through the disposable database's migration role; the application role intentionally lacks
        // broader PostgreSQL activity-monitoring privileges.
        JdbcTemplate observer = new JdbcTemplate(migrationDataSource());
        long deadline = System.nanoTime() + LOCK_OBSERVATION_TIMEOUT.toNanos();
        int observedPids = 0;
        int observedNames = 0;
        // Unique per-contender application_name values exclude unrelated sessions in the disposable database.
        // The park only throttles catalog polling; success depends on observing both named backend PIDs waiting.
        while (System.nanoTime() < deadline) {
            List<Integer> counts = observer.execute((ConnectionCallback<List<Integer>>) connection -> {
                try (PreparedStatement statement = connection.prepareStatement(query)) {
                    statement.setQueryTimeout(5);
                    for (int index = 0; index < applicationNames.length; index++) {
                        statement.setString(index + 1, applicationNames[index]);
                    }
                    try (ResultSet result = statement.executeQuery()) {
                        result.next();
                        return List.of(result.getInt(1), result.getInt(2));
                    }
                }
            });
            observedNames = counts == null ? 0 : counts.get(0);
            observedPids = counts == null ? 0 : counts.get(1);
            if (observedNames == applicationNames.length && observedPids == applicationNames.length) {
                return;
            }
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
            if (Thread.interrupted()) {
                throw new InterruptedException("Interrupted while observing PostgreSQL lock waiters");
            }
        }
        throw new AssertionError("Expected PostgreSQL lock waits from " + Arrays.toString(applicationNames) +
                ", observed " + observedNames + " tagged sessions and " + observedPids + " backend PIDs");
    }

    private void awaitLatch(CountDownLatch latch, String description) {
        awaitLatch(latch, description, Duration.ofSeconds(10));
    }

    private void awaitLatch(CountDownLatch latch, String description, Duration timeout) {
        try {
            if (!latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Timed out waiting for " + description);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for " + description, interrupted);
        }
    }

    private void persistModelReferenceAndAttach(MediaAssetOperationLease operation, long assetId) {
        persistModelReferenceAndAttach(operation, assetId, "media-reference-setup");
    }

    private void persistModelReferenceAndAttach(MediaAssetOperationLease operation, long assetId,
                                                String applicationName) {
        transactionTemplate().executeWithoutResult(status -> {
            configureWorkerDatabaseSession(applicationName);
            persistModelReference(operation, assetId);
            mediaAssetLifecycle.attach(operation, operation.mediaAssetIds());
        });
    }

    private void configureWorkerDatabaseSession(String applicationName) {
        jdbc.queryForObject("SELECT set_config('application_name', ?, true)", String.class, applicationName);
        jdbc.queryForObject("SELECT set_config('lock_timeout', ?, true)", String.class, DATABASE_LOCK_TIMEOUT);
        jdbc.queryForObject("SELECT set_config('statement_timeout', ?, true)", String.class,
                DATABASE_STATEMENT_TIMEOUT);
    }

    private void persistModelReference(MediaAssetOperationLease operation, long assetId) {
        String publicId = jdbc.queryForObject("SELECT public_id FROM media_asset WHERE id = ?", String.class, assetId);
        Model3d model = Model3d.builder()
                .sourceType(ModelType.DESIGNER_MODEL)
                .status(ModelStatus.PENDING)
                .name("Concurrency model " + UUID.randomUUID())
                .filePath3d(publicId)
                .mediaAsset(entityManager.getReference(MediaAsset.class, assetId))
                .build();
        entityManager.persist(model);
        entityManager.flush();
    }

    private record ReadyMediaFixture(MediaAssetOperationLease operation) {
    }

    private enum RaceOutcome {
        ATTACHED,
        CLEANUP_REQUESTED,
        REJECTED
    }

    private void insertMediaAsset(UUID operationId, String publicId, String providerAssetId) {
        jdbc.update("""
                INSERT INTO media_asset (
                    operation_id, media_kind, provider, cloud_name, provider_asset_id, public_id,
                    resource_type, delivery_type, format, byte_count, state)
                VALUES (?, 'IMAGE', 'CLOUDINARY', 'test-cloud', ?, ?, 'image', 'upload', 'png', 16, 'READY')
                """, operationId, providerAssetId, publicId);
    }

    private static MediaAsset mediaAsset(MediaKind kind, String publicId) {
        String resourceType = kind == MediaKind.MODEL_3D ? "raw" : "image";
        String format = kind == MediaKind.MODEL_3D ? "glb" : "png";
        return MediaAsset.builder()
                .operationId(UUID.randomUUID())
                .mediaKind(kind)
                .provider(MediaProvider.CLOUDINARY)
                .cloudName("test-cloud")
                .providerAssetId(UUID.randomUUID().toString())
                .publicId(publicId)
                .resourceType(resourceType)
                .deliveryType("upload")
                .format(format)
                .byteCount(16L)
                .providerVersion(1L)
                .secureUrl("https://res.cloudinary.com/test-cloud/" + resourceType + "/upload/v1/" + publicId +
                        (kind == MediaKind.MODEL_3D ? "" : "." + format))
                .state(MediaAssetState.READY)
                .build();
    }
}
