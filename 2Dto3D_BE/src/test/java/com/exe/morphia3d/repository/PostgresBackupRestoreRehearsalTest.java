package com.exe.morphia3d.repository;

import com.exe.morphia3d.Morphia3dApplication;
import com.exe.morphia3d.entity.Color;
import com.exe.morphia3d.entity.Material;
import com.exe.morphia3d.entity.MaterialColor;
import com.exe.morphia3d.entity.MaterialColorId;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.exe.morphia3d.test.support.PostgresTestRoleProvisioning;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.ExecConfig;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Disposable, synthetic-data-only pg_dump/pg_restore rehearsal. This class intentionally fails when
 * Docker is unavailable and suppresses PostgreSQL image pulls; a missing pinned image is an environment error.
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
class PostgresBackupRestoreRehearsalTest {
    private static final Map<String, String> V4_STRING_LIST_CHECKS = Map.of(
            "tasks.ck_tasks_priority", stringListCheckDefinition("priority", List.of("LOW", "NORMAL", "HIGH", "URGENT"), false),
            "tasks.ck_tasks_status", stringListCheckDefinition("status",
                    List.of("ASSIGNED", "IN_PROGRESS", "SUBMITTED", "REVISION_REQUESTED", "APPROVED"), false),
            "task_history.ck_task_history_action", stringListCheckDefinition("action",
                    List.of("TASK_CREATED", "TASK_UPDATED", "TASK_REASSIGNED", "TASK_STARTED", "TASK_SUBMITTED",
                            "REVIEW_APPROVED", "REVISION_REQUESTED"), false),
            "task_history.ck_task_history_previous_status", stringListCheckDefinition("previous_status",
                    List.of("ASSIGNED", "IN_PROGRESS", "SUBMITTED", "REVISION_REQUESTED", "APPROVED"), true),
            "task_history.ck_task_history_new_status", stringListCheckDefinition("new_status",
                    List.of("ASSIGNED", "IN_PROGRESS", "SUBMITTED", "REVISION_REQUESTED", "APPROVED"), true),
            "ai_generation_jobs.ck_ai_generation_jobs_status", stringListCheckDefinition("status",
                    List.of("PENDING", "PROCESSING", "COMPLETED", "FAILED"), false),
            "payos_merchant_bindings.ck_payos_binding_state", stringListCheckDefinition("binding_state",
                    List.of("PENDING_VERIFICATION", "VERIFIED", "REVOKED"), false),
            "payos_merchant_binding_audit.ck_payos_binding_audit_event", stringListCheckDefinition("event_type",
                    List.of("BINDING_REGISTERED", "BINDING_VERIFIED", "BINDING_REVOKED", "LIVE_LINK_AUTHORIZED"), false));
    private static final List<String> SCHEMA_DESCRIPTOR_CATEGORIES = List.of(
            "COLUMN", "CONSTRAINT", "INDEX", "SEQUENCE", "SCHEMA_OWNER", "OBJECT_OWNER",
            "SCHEMA_ACL", "OBJECT_ACL");
    private static final int MAX_SCHEMA_MISMATCHES_PER_SIDE = 6;
    private static final int MAX_SCHEMA_DESCRIPTOR_LENGTH = 240;
    private static final int MAX_SCHEMA_DIAGNOSTIC_LENGTH = 6_000;


    private static final String ADMIN_USER = "restore_admin";
    private static final String MIGRATION_USER = "restore_migrator";
    private static final String APPLICATION_USER = "restore_application";
    private static final String BACKUP_USER = "restore_backup_reader";

    // Values are generated per test JVM and are only passed to disposable PostgreSQL processes.
    private static final String ADMIN_PASSWORD = newPassword();
    private static final String MIGRATION_PASSWORD = newPassword();
    private static final String APPLICATION_PASSWORD = newPassword();
    private static final String BACKUP_PASSWORD = newPassword();

    private static final String SOURCE_DATABASE = "restore_rehearsal_source";
    private static final String RESTORE_DATABASE = "restore_rehearsal_target";
    private static final String ARCHIVE_PATH = "/tmp/morphia-postgres-restore-rehearsal.dump";
    private static final String LEGACY_MODEL_PATH = "legacy/models/rehearsal-chair.glb";
    private static final String LEGACY_IMAGE_PATH = "legacy/images/rehearsal-chair-cover";
    private static final UUID OPERATION_ID = UUID.fromString("00000000-0000-4000-8000-000000000041");
    private static final String TEST_CLOUD = "synthetic-rehearsal-cloud";
    private static final String POSTGRES_IMAGE_DIGEST =
            "postgres@sha256:721873c34ceb9f8d8fc265984940dc982404c105f19ad51be9fdc5970a6080ea";
    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse(POSTGRES_IMAGE_DIGEST)
            .asCompatibleSubstituteFor("postgres");

    @org.testcontainers.junit.jupiter.Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE)
            .withImagePullPolicy(image -> false)
            .withDatabaseName("postgres")
            .withUsername(ADMIN_USER)
            .withPassword(ADMIN_PASSWORD);

    private static boolean rehearsalPrepared;
    private static String archiveListing;
    private static String sourceVersion;
    private static String sourceImageDigest;
    private static String pgDumpVersion;
    private static String pgRestoreVersion;
    private static int baselineHistoryRows;
    private static FixtureIds fixtureIds;
    private static DatabaseSnapshot sourceSnapshot;
    private static DatabaseSnapshot restoredSnapshot;
    private static String restoredSchemaFingerprintBeforeSpring;

    @Autowired
    private JdbcTemplate applicationJdbc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private Environment environment;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @DynamicPropertySource
    static void restoredDatabaseProperties(DynamicPropertyRegistry registry) {
        prepareBackupRestoreRehearsal();
        registry.add("spring.datasource.url", () -> databaseUrl(RESTORE_DATABASE));
        registry.add("spring.datasource.username", () -> APPLICATION_USER);
        registry.add("spring.datasource.password", () -> APPLICATION_PASSWORD);
    }

    private static synchronized void prepareBackupRestoreRehearsal() {
        if (rehearsalPrepared) {
            return;
        }
        if (!POSTGRES.isRunning()) {
            throw new IllegalStateException("Testcontainers must start the pinned PostgreSQL image before setup");
        }

        sourceImageDigest = POSTGRES.getDockerImageName();
        if (!POSTGRES_IMAGE_DIGEST.equals(sourceImageDigest)) {
            throw new IllegalStateException("The disposable test container did not use the pinned PostgreSQL digest");
        }

        provisionRolesAndDatabases();
        DataSource sourceMigrationDataSource = migrationDataSource(SOURCE_DATABASE);
        applyFreshMigrationsAndSeed(sourceMigrationDataSource);
        grantSourcePrivileges(adminDataSource(SOURCE_DATABASE));

        sourceVersion = queryString(sourceMigrationDataSource, "SHOW server_version");
        requirePostgresVersion(sourceVersion);
        assertBackupRoleIsReadLimited(sourceMigrationDataSource, SOURCE_DATABASE);

        sourceSnapshot = snapshot(sourceMigrationDataSource, SOURCE_DATABASE, sourceVersion, sourceImageDigest);
        baselineHistoryRows = count(sourceMigrationDataSource,
                "SELECT COUNT(*) FROM flyway_schema_history WHERE type = 'BASELINE'");
        if (baselineHistoryRows != 0) {
            throw new IllegalStateException("Fresh V1/V2 source unexpectedly contains a Flyway baseline row");
        }

        createCustomArchive();
        createAndPrepareEmptyRestoreDatabase();
        restoreCustomArchive();

        DataSource restoredMigrationDataSource = migrationDataSource(RESTORE_DATABASE);
        String restoredVersion = queryString(restoredMigrationDataSource, "SHOW server_version");
        requirePostgresVersion(restoredVersion);
        restoredSnapshot = snapshot(restoredMigrationDataSource, RESTORE_DATABASE, restoredVersion, sourceImageDigest);
        assertSnapshotsMatch(sourceSnapshot, restoredSnapshot);
        assertMigrationHistory(restoredMigrationDataSource);
        flyway(restoredMigrationDataSource).load().validate();
        assertMigrationRoleOwnsRestoredObjects(restoredMigrationDataSource);
        assertApplicationAndBackupPrivileges(restoredMigrationDataSource, RESTORE_DATABASE);
        restoredSchemaFingerprintBeforeSpring = restoredSnapshot.schemaFingerprint();
        rehearsalPrepared = true;
    }

    private static void provisionRolesAndDatabases() {
        JdbcTemplate admin = new JdbcTemplate(adminDataSource());
        admin.execute("CREATE ROLE " + MIGRATION_USER + " LOGIN PASSWORD '" + MIGRATION_PASSWORD + "'");
        PostgresTestRoleProvisioning.provisionMigrationRoles(admin, MIGRATION_USER);
        admin.execute("CREATE ROLE " + APPLICATION_USER + " LOGIN PASSWORD '" + APPLICATION_PASSWORD + "'");
        admin.execute("CREATE ROLE " + BACKUP_USER + " LOGIN PASSWORD '" + BACKUP_PASSWORD + "'");

        createDatabase(admin, SOURCE_DATABASE);
        createDatabase(admin, RESTORE_DATABASE);
        grantOwnerSchemaPrivileges(SOURCE_DATABASE);
        grantDatabaseConnect(admin, SOURCE_DATABASE);
        grantDatabaseConnect(admin, RESTORE_DATABASE);
        assertDatabaseOwnedByMigration(SOURCE_DATABASE);
        assertDatabaseOwnedByMigration(RESTORE_DATABASE);
    }

    private static void grantOwnerSchemaPrivileges(String databaseName) {
        new JdbcTemplate(adminDataSource(databaseName)).execute(
                "GRANT USAGE, CREATE ON SCHEMA public TO admin3d_owner, admin3d_lock_owner, " +
                        "admin3d_command_owner, payos_trust_owner");
    }

    private static void createDatabase(JdbcTemplate admin, String databaseName) {
        admin.execute("CREATE DATABASE " + databaseName + " OWNER " + MIGRATION_USER);
        admin.execute("ALTER DATABASE " + databaseName + " SET timezone TO 'UTC'");
        admin.execute("ALTER DATABASE " + databaseName + " SET DateStyle TO 'ISO, YMD'");
    }

    private static void grantDatabaseConnect(JdbcTemplate admin, String databaseName) {
        admin.execute("REVOKE CONNECT ON DATABASE " + databaseName + " FROM PUBLIC");
        admin.execute("GRANT CONNECT ON DATABASE " + databaseName + " TO " + MIGRATION_USER);
        admin.execute("GRANT CONNECT ON DATABASE " + databaseName + " TO " + APPLICATION_USER);
        admin.execute("GRANT CONNECT ON DATABASE " + databaseName + " TO " + BACKUP_USER);
    }

    private static void assertDatabaseOwnedByMigration(String databaseName) {
        String owner = queryString(adminDataSource(databaseName), """
                SELECT pg_get_userbyid(datdba)
                FROM pg_database
                WHERE datname = current_database()
                """);
        if (!MIGRATION_USER.equals(owner)) {
            throw new IllegalStateException("Migration role must own each rehearsal database");
        }
    }

    private static void applyFreshMigrationsAndSeed(DataSource sourceDataSource) {
        FluentConfiguration throughV1 = flyway(sourceDataSource).target(MigrationVersion.fromVersion("1"));
        int v1Count = throughV1.load().migrate().migrationsExecuted;
        if (v1Count != 1) {
            throw new IllegalStateException("Fresh rehearsal source did not apply exactly V1");
        }

        FixtureIds ids = seedLegacyFixtures(sourceDataSource);
        int v2Count = flyway(sourceDataSource).target(MigrationVersion.fromVersion("2"))
                .load().migrate().migrationsExecuted;
        if (v2Count != 1) {
            throw new IllegalStateException("Fresh rehearsal source did not apply exactly V2");
        }
        fixtureIds = seedMediaFixtures(sourceDataSource, ids);
        Flyway throughV3 = flyway(sourceDataSource).target(MigrationVersion.fromVersion("3")).load();
        int v3Count = throughV3.migrate().migrationsExecuted;
        if (v3Count != 1) {
            throw new IllegalStateException("Fresh rehearsal source did not apply exactly V3");
        }
        throughV3.validate();
        List<String> historicalVersions = new JdbcTemplate(sourceDataSource).queryForList(
                "SELECT version FROM flyway_schema_history ORDER BY installed_rank", String.class);
        if (!historicalVersions.equals(List.of("1", "2", "3"))) {
            throw new IllegalStateException("Historical rehearsal stage must contain successful V1/V2/V3 only");
        }

        Flyway throughV14 = flyway(sourceDataSource).target(MigrationVersion.fromVersion("14")).load();
        int currentMigrations = throughV14.migrate().migrationsExecuted;
        if (currentMigrations != 11) {
            throw new IllegalStateException("Current rehearsal schema must apply V4 through V14 after historical V3");
        }
        JdbcTemplate bootstrap = new JdbcTemplate(sourceDataSource);
        PostgresTestRoleProvisioning.grantSnapshotReferencePrivileges(bootstrap);
        Flyway currentSchema = flyway(sourceDataSource).load();
        if (currentSchema.migrate().migrationsExecuted != 7) {
            throw new IllegalStateException("Current rehearsal schema must apply V15 through V21");
        }
        currentSchema.validate();
    }

    private static FixtureIds seedLegacyFixtures(DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Integer userId = jdbc.queryForObject("""
                INSERT INTO users (name, email, password, role, status)
                VALUES ('Synthetic Restore User', 'restore-fixture@example.invalid', 'synthetic-password-hash',
                        'DESIGNER', 'ACTIVE')
                RETURNING user_id
                """, Integer.class);
        Integer materialId = jdbc.queryForObject("""
                INSERT INTO materials (name, type, price_per_gram, stock_quantity, unit, is_available)
                VALUES ('Synthetic Restore PLA', 'PLA', 0.05, 125.0, 'g', 'AVAILABLE')
                RETURNING material_id
                """, Integer.class);
        Integer colorId = jdbc.queryForObject("""
                INSERT INTO colors (name, hex_code) VALUES ('Synthetic Restore Blue', '#13579B')
                RETURNING color_id
                """, Integer.class);
        jdbc.update("INSERT INTO material_colors (material_id, color_id, quantity) VALUES (?, ?, ?)",
                materialId, colorId, 25.0d);
        jdbc.update("""
                INSERT INTO material_stock_logs (material_id, change_type, quantity, note, created_by, created_at)
                VALUES (?, 'ADJUSTMENT', 125.0, 'synthetic backup rehearsal fixture', ?,
                        TIMESTAMP '2026-01-02 03:04:05')
                """, materialId, userId);
        Integer modelId = jdbc.queryForObject("""
                INSERT INTO model_3d (source_type, user_id, name, slug, description, file_path_3d,
                                     size_x_mm, size_y_mm, size_z_mm, weight, category, model_status, created_at)
                VALUES ('DESIGNER_MODEL', ?, 'Synthetic Restore Chair', 'synthetic-restore-chair',
                        'Synthetic database fixture', ?, 100, 80, 90, 2.5, 'Furniture', 'ACTIVE',
                        TIMESTAMP '2026-01-02 03:04:05')
                RETURNING model_id
                """, Integer.class, userId, LEGACY_MODEL_PATH);
        Integer imageId = jdbc.queryForObject("""
                INSERT INTO model_images (model_id, image_path, is_primary, uploaded_at)
                VALUES (?, ?, TRUE, TIMESTAMP '2026-01-02 03:04:05')
                RETURNING image_id
                """, Integer.class, modelId, LEGACY_IMAGE_PATH);
        jdbc.update("INSERT INTO model_parts (model_id, part_name, part_key, color_id) VALUES (?, ?, ?, ?)",
                modelId, "Synthetic seat", "synthetic-seat", colorId);
        return new FixtureIds(userId, materialId, colorId, modelId, imageId, null, null);
    }

    private static FixtureIds seedMediaFixtures(DataSource dataSource, FixtureIds ids) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Long modelAssetId = jdbc.queryForObject("""
                INSERT INTO media_asset (operation_id, media_kind, provider, cloud_name, provider_asset_id,
                                         public_id, resource_type, delivery_type, format, byte_count,
                                         provider_version, secure_url, state)
                VALUES (?, 'MODEL_3D', 'CLOUDINARY', ?, 'synthetic-model-asset',
                        'products/rehearsal/synthetic-chair.glb', 'raw', 'upload', 'glb', 2048,
                        1, 'https://res.cloudinary.com/synthetic-rehearsal-cloud/raw/upload/v1/products/rehearsal/synthetic-chair.glb',
                        'ATTACHED')
                RETURNING id
                """, Long.class, OPERATION_ID, TEST_CLOUD);
        Long imageAssetId = jdbc.queryForObject("""
                INSERT INTO media_asset (operation_id, media_kind, provider, cloud_name, provider_asset_id,
                                         public_id, resource_type, delivery_type, format, byte_count,
                                         provider_version, secure_url, state)
                VALUES (?, 'IMAGE', 'CLOUDINARY', ?, 'synthetic-image-asset',
                        'products/rehearsal/synthetic-chair-cover', 'image', 'upload', 'png', 1024,
                        1, 'https://res.cloudinary.com/synthetic-rehearsal-cloud/image/upload/v1/products/rehearsal/synthetic-chair-cover.png',
                        'ATTACHED')
                RETURNING id
                """, Long.class, OPERATION_ID, TEST_CLOUD);
        jdbc.update("UPDATE model_3d SET media_asset_id = ? WHERE model_id = ?", modelAssetId, ids.modelId());
        jdbc.update("UPDATE model_images SET media_asset_id = ? WHERE image_id = ?", imageAssetId, ids.imageId());
        return new FixtureIds(ids.userId(), ids.materialId(), ids.colorId(), ids.modelId(), ids.imageId(),
                modelAssetId, imageAssetId);
    }

    private static void grantSourcePrivileges(DataSource sourceDataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(sourceDataSource);
        jdbc.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO " + APPLICATION_USER + ", " + BACKUP_USER);
        jdbc.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO " + APPLICATION_USER);
        jdbc.execute("GRANT SELECT ON TABLE flyway_schema_history TO " + APPLICATION_USER);
        jdbc.execute("REVOKE INSERT, UPDATE, DELETE ON TABLE flyway_schema_history FROM " + APPLICATION_USER);
        jdbc.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO " + APPLICATION_USER);
        jdbc.execute("GRANT SELECT ON ALL TABLES IN SCHEMA public TO " + BACKUP_USER);
        jdbc.execute("GRANT SELECT ON ALL SEQUENCES IN SCHEMA public TO " + BACKUP_USER);
    }

    private static void assertBackupRoleIsReadLimited(DataSource dataSource, String databaseName) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(privilege(jdbc, "SELECT has_database_privilege(?, ?, 'CONNECT')", BACKUP_USER, databaseName))
                .isTrue();
        assertThat(privilege(jdbc, "SELECT has_schema_privilege(?, 'public', 'USAGE')", BACKUP_USER)).isTrue();
        assertThat(privilege(jdbc, "SELECT has_schema_privilege(?, 'public', 'CREATE')", BACKUP_USER)).isFalse();
        assertThat(privilege(jdbc, "SELECT has_table_privilege(?, 'public.media_asset', 'SELECT')", BACKUP_USER))
                .isTrue();
        for (String operation : List.of("INSERT", "UPDATE", "DELETE")) {
            assertThat(privilege(jdbc,
                    "SELECT has_table_privilege(?, 'public.media_asset', '" + operation + "')", BACKUP_USER))
                    .isFalse();
        }
        assertThat(privilege(jdbc,
                "SELECT has_sequence_privilege(?, 'public.media_asset_id_seq', 'SELECT')", BACKUP_USER)).isTrue();
        assertThat(privilege(jdbc,
                "SELECT has_sequence_privilege(?, 'public.media_asset_id_seq', 'USAGE')", BACKUP_USER)).isFalse();
    }

    private static boolean privilege(JdbcTemplate jdbc, String sql, Object... args) {
        return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, args));
    }

    private static void createCustomArchive() {
        Container.ExecResult pgDump = execAs(BACKUP_PASSWORD, "pg_dump", "--version");
        requireExec("pg_dump --version", pgDump, "pg_dump version check");
        pgDumpVersion = pgDump.getStdout().trim();
        requireClientVersion(pgDumpVersion, "pg_dump");
        Container.ExecResult dump = execAs(BACKUP_PASSWORD,
                "pg_dump", "--format=custom", "--file", ARCHIVE_PATH,
                "--host", "127.0.0.1", "--port", "5432",
                "--username", BACKUP_USER, "--dbname", SOURCE_DATABASE);
        requireExec("pg_dump -Fc", dump, "custom archive creation");

        Container.ExecResult pgRestore = exec("pg_restore", "--version");
        requireExec("pg_restore --version", pgRestore, "pg_restore version check");
        pgRestoreVersion = pgRestore.getStdout().trim();
        requireClientVersion(pgRestoreVersion, "pg_restore");
        Container.ExecResult listing = exec("pg_restore", "--list", ARCHIVE_PATH);
        requireExec("pg_restore -l", listing, "custom archive listing");
        archiveListing = listing.getStdout();
        for (String requiredEntry : List.of("flyway_schema_history", "materials", "material_stock_logs",
                "model_3d", "model_images", "media_asset", "SEQUENCE SET", "ACL")) {
            if (!archiveListing.contains(requiredEntry)) {
                throw new IllegalStateException("Custom archive listing omitted a required schema/data entry");
            }
        }
    }

    private static void createAndPrepareEmptyRestoreDatabase() {
        JdbcTemplate target = new JdbcTemplate(adminDataSource(RESTORE_DATABASE));
        Integer tableCount = target.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                """, Integer.class);
        if (tableCount == null || tableCount != 0) {
            throw new IllegalStateException("Restore target database is not empty");
        }
        target.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
        target.execute("GRANT USAGE ON SCHEMA public TO " + APPLICATION_USER + ", " + BACKUP_USER);
    }

    private static void restoreCustomArchive() {
        JdbcTemplate restoreAdmin = new JdbcTemplate(adminDataSource(RESTORE_DATABASE));
        restoreAdmin.execute("GRANT CREATE ON SCHEMA public TO payos_trust_owner");
        Container.ExecResult restore = execAs(ADMIN_PASSWORD,
                "pg_restore", "--exit-on-error",
                "--host", "127.0.0.1", "--port", "5432",
                "--username", ADMIN_USER, "--dbname", RESTORE_DATABASE, ARCHIVE_PATH);
        restoreAdmin.execute("REVOKE CREATE ON SCHEMA public FROM payos_trust_owner");
        requireExec("pg_restore --exit-on-error", restore, "custom archive restore");
    }

    private static Container.ExecResult execAs(String password, String... command) {
        return execute(ExecConfig.builder().command(command).envVars(Map.of("PGPASSWORD", password)).build());
    }

    private static Container.ExecResult exec(String... command) {
        return execute(ExecConfig.builder().command(command).build());
    }

    private static Container.ExecResult execute(ExecConfig config) {
        try {
            return POSTGRES.execInContainer(StandardCharsets.UTF_8, config);
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Disposable PostgreSQL client command could not complete", exception);
        }
    }

    private static void requireExec(String operation, Container.ExecResult result, String failureDescription) {
        if (result.getExitCode() != 0) {
            String diagnostic = sanitizeDiagnostic(result.getStderr());
            throw new IllegalStateException(operation + " failed during " + failureDescription +
                    " (exit " + result.getExitCode() + "): " + diagnostic);
        }
    }

    private static String sanitizeDiagnostic(String diagnostic) {
        if (diagnostic == null || diagnostic.isBlank()) {
            return "no client diagnostic";
        }
        return diagnostic.replaceAll("(?i)(password|pgpassword)\\s*[=:]\\s*\\S+", "$1=[REDACTED]")
                .replaceAll("[\\r\\n]+", " ").trim();
    }

    private static DatabaseSnapshot snapshot(DataSource dataSource, String databaseName,
                                             String postgresVersion, String imageDigest) {
        SchemaDescriptorSnapshot schema = schemaDescriptors(dataSource);
        return new DatabaseSnapshot(sha256(String.join("\n", schema.descriptors())), schema.descriptors(),
                schema.rawConstraintDescriptors(), schema.unverifiedExpectedChecks(), tableDigests(dataSource),
                flywayHistory(dataSource), sequenceStates(dataSource), objectOwners(dataSource, databaseName),
                normalizedPrivileges(dataSource, databaseName), postgresVersion, imageDigest);
    }

    private static SchemaDescriptorSnapshot schemaDescriptors(DataSource dataSource) {
        List<String> entries = new ArrayList<>();
        entries.addAll(queryStrings(dataSource, """
                SELECT concat_ws('|', 'COLUMN', table_name, column_name, ordinal_position::text,
                    data_type, is_nullable, coalesce(column_default, ''), is_identity,
                    coalesce(identity_generation, ''))
                FROM information_schema.columns
                WHERE table_schema = 'public'
                ORDER BY table_name COLLATE "C", ordinal_position
                """));
        List<MigrationCheckConstraintCanonicalizer.ConstraintDescriptor> constraints = new JdbcTemplate(dataSource).query("""
                SELECT relation.relname, constraint_row.conname, constraint_row.contype::text,
                    pg_get_constraintdef(constraint_row.oid, true), constraint_row.convalidated,
                    constraint_row.conislocal, constraint_row.coninhcount, constraint_row.connoinherit,
                    (constraint_row.conparentid <> 0)
                FROM pg_constraint constraint_row
                JOIN pg_class relation ON relation.oid = constraint_row.conrelid
                JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
                WHERE schema_row.nspname = 'public'
                ORDER BY relation.relname COLLATE "C", constraint_row.conname COLLATE "C"
                """, (resultSet, rowNumber) -> new MigrationCheckConstraintCanonicalizer.ConstraintDescriptor(
                resultSet.getString(1), resultSet.getString(2), resultSet.getString(3), resultSet.getString(4),
                resultSet.getBoolean(5), resultSet.getBoolean(6), resultSet.getInt(7), resultSet.getBoolean(8),
                resultSet.getBoolean(9)));
        constraints = normalizeStringListChecks(constraints);
        MigrationCheckConstraintCanonicalizer.Canonicalization canonicalConstraints =
                MigrationCheckConstraintCanonicalizer.canonicalize(constraints);
        entries.addAll(canonicalConstraints.descriptors());
        List<String> indexes = queryStrings(dataSource, """
                SELECT concat_ws('|', 'INDEX', tablename, indexname, indexdef)
                FROM pg_indexes WHERE schemaname = 'public'
                ORDER BY tablename COLLATE "C", indexname COLLATE "C"
                """);
        entries.addAll(normalizeAiGenerationPendingClaimIndex(indexes));
        entries.addAll(queryStrings(dataSource, """
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
                ORDER BY sequence_row.relname COLLATE "C"
                """));
        entries.addAll(objectOwners(dataSource, null));
        entries.addAll(queryStrings(dataSource, """
                SELECT concat_ws('|', 'SCHEMA_ACL', schema_row.nspname,
                    coalesce(grantee.rolname, 'PUBLIC'), permission.privilege_type,
                    permission.is_grantable::text)
                FROM pg_namespace schema_row
                CROSS JOIN LATERAL aclexplode(coalesce(schema_row.nspacl,
                    acldefault('n'::\"char\", schema_row.nspowner))) permission
                LEFT JOIN pg_roles grantee ON grantee.oid = permission.grantee
                WHERE schema_row.nspname = 'public'
                ORDER BY grantee.rolname COLLATE "C" NULLS FIRST, permission.privilege_type COLLATE "C"
                """));
        entries.addAll(queryStrings(dataSource, """
                SELECT concat_ws('|', 'OBJECT_ACL', relation.relname, relation.relkind::text,
                    coalesce(grantee.rolname, 'PUBLIC'), permission.privilege_type,
                    permission.is_grantable::text)
                FROM pg_class relation
                JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
                CROSS JOIN LATERAL aclexplode(coalesce(relation.relacl,
                    acldefault(CASE WHEN relation.relkind = 'S' THEN 'S' ELSE 'r' END::\"char\",
                               relation.relowner))) permission
                LEFT JOIN pg_roles grantee ON grantee.oid = permission.grantee
                WHERE schema_row.nspname = 'public'
                    AND relation.relkind IN ('r', 'p', 'S', 'v', 'm', 'f', 'i', 'I')
                ORDER BY relation.relname COLLATE "C", grantee.rolname COLLATE "C" NULLS FIRST,
                         permission.privilege_type COLLATE "C"
                """));
        entries.sort(String::compareTo);
        return new SchemaDescriptorSnapshot(List.copyOf(entries), canonicalConstraints.rawDefinitions(),
                canonicalConstraints.unverifiedExpectedChecks());
    }

    private static List<MigrationCheckConstraintCanonicalizer.ConstraintDescriptor> normalizeStringListChecks(
            List<MigrationCheckConstraintCanonicalizer.ConstraintDescriptor> descriptors) {
        Set<String> found = new TreeSet<>();
        List<MigrationCheckConstraintCanonicalizer.ConstraintDescriptor> normalized = new ArrayList<>(descriptors.size());
        for (MigrationCheckConstraintCanonicalizer.ConstraintDescriptor descriptor : descriptors) {
            String key = descriptor.table() + "." + descriptor.name();
            String expectedDefinition = V4_STRING_LIST_CHECKS.get(key);
            if (expectedDefinition == null) {
                normalized.add(descriptor);
                continue;
            }
            if (!"c".equals(descriptor.type()) || !descriptor.validated() || !descriptor.local()
                    || descriptor.inheritanceCount() != 0 || descriptor.noInherit() || descriptor.hasParent()) {
                throw new IllegalStateException("String-list CHECK constraint metadata changed: " + key);
            }
            String normalizedDefinition = descriptor.definition()
                    .replace("::character varying::text", "::character varying")
                    .replace("]::text[]", "]");
            if (!expectedDefinition.equals(normalizedDefinition)) {
                throw new IllegalStateException("String-list CHECK constraint contract changed: " + key + " -> "
                        + descriptor.definition());
            }
            found.add(key);
            normalized.add(new MigrationCheckConstraintCanonicalizer.ConstraintDescriptor(
                    descriptor.table(), descriptor.name(), descriptor.type(), expectedDefinition,
                    descriptor.validated(), descriptor.local(), descriptor.inheritanceCount(),
                    descriptor.noInherit(), descriptor.hasParent()));
        }
        if (!found.equals(V4_STRING_LIST_CHECKS.keySet())) {
            throw new IllegalStateException("Missing expected string-list CHECK constraints in backup rehearsal: "
                    + V4_STRING_LIST_CHECKS.keySet().stream().filter(key -> !found.contains(key)).sorted().toList());
        }
        return List.copyOf(normalized);
    }

    private static List<String> normalizeAiGenerationPendingClaimIndex(List<String> descriptors) {
        String prefix = "INDEX|ai_generation_jobs|ix_ai_generation_jobs_pending_claim|";
        String expected = "CREATEINDEXix_ai_generation_jobs_pending_claimONpublic.ai_generation_jobs"
                + "USINGbtreestatus,processing_lease_until,created_atWHEREstatus=ANYARRAY['PENDING','PROCESSING']";
        String paymentPrefix = "INDEX|payment_attempts|uq_payment_attempts_one_active_per_order|";
        String paymentExpected = "CREATEUNIQUEINDEXuq_payment_attempts_one_active_per_order"
                + "ONpublic.payment_attemptsUSINGbtreeorder_idWHEREstatus=ANYARRAY"
                + "['INITIATED','SUBMITTING','PENDING','UNKNOWN','LEGACY_REVIEW']";
        String pipelinePrefix = "INDEX|design_pipeline_jobs|ix_design_pipeline_jobs_claim|";
        String pipelineExpected = "CREATEINDEXix_design_pipeline_jobs_claimONpublic.design_pipeline_jobs"
                + "USINGbtreestatus,next_attempt_at,lease_until,created_atWHEREstatus=ANYARRAY['PENDING','PROCESSING']";
        List<String> normalized = new ArrayList<>(descriptors.size());
        for (String descriptor : descriptors) {
            if (descriptor.startsWith(paymentPrefix)) {
                String definition = descriptor.substring(paymentPrefix.length());
                String canonical = definition.replaceAll("\\s+", "")
                        .replace("::charactervarying", "")
                        .replace("::text[]", "")
                        .replace("::text", "")
                        .replace("(", "")
                        .replace(")", "");
                if (!paymentExpected.equals(canonical)) {
                    throw new IllegalStateException("Payment active-attempt index contract changed: " + definition);
                }
                normalized.add(paymentPrefix + paymentExpected);
                continue;
            }
            if (descriptor.startsWith(pipelinePrefix)) {
                String definition = descriptor.substring(pipelinePrefix.length());
                String canonical = definition.replaceAll("\\s+", "")
                        .replace("::charactervarying", "")
                        .replace("::text[]", "")
                        .replace("::text", "")
                        .replace("(", "")
                        .replace(")", "");
                if (!pipelineExpected.equals(canonical)) {
                    throw new IllegalStateException("Design pipeline claim-index contract changed: " + definition);
                }
                normalized.add(pipelinePrefix + pipelineExpected);
                continue;
            }
            if (!descriptor.startsWith(prefix)) {
                normalized.add(descriptor);
                continue;
            }
            String definition = descriptor.substring(prefix.length());
            String canonical = definition.replaceAll("\\s+", "")
                    .replace("::charactervarying", "")
                    .replace("::text[]", "")
                    .replace("::text", "")
                    .replace("(", "")
                    .replace(")", "");
            if (!expected.equals(canonical)) {
                throw new IllegalStateException("AI generation pending-claim index contract changed: " + definition);
            }
            normalized.add(prefix + expected);
        }
        if (normalized.stream().noneMatch(value -> value.startsWith(prefix))) {
            throw new IllegalStateException("AI generation pending-claim index is missing from backup rehearsal");
        }
        if (normalized.stream().noneMatch(value -> value.startsWith(pipelinePrefix))) {
            throw new IllegalStateException("Design pipeline claim index is missing from backup rehearsal");
        }
        return List.copyOf(normalized);
    }

    private static String stringListCheckDefinition(String column, List<String> values, boolean nullable) {
        String literals = String.join(", ", values.stream()
                .map(value -> "'" + value + "'::character varying").toList());
        String membership = column + "::text = ANY (ARRAY[" + literals + "])";
        return nullable
                ? "CHECK (" + column + " IS NULL OR (" + membership + "))"
                : "CHECK (" + membership + ")";
    }

    private static List<String> objectOwners(DataSource dataSource, String databaseName) {
        List<String> entries = new ArrayList<>();
        if (databaseName != null) {
            List<String> databaseOwners = queryStrings(dataSource, """
                    SELECT concat_ws('|', 'DATABASE_OWNER', pg_get_userbyid(datdba))
                    FROM pg_database WHERE datname = ?
                    """, databaseName);
            if (databaseOwners.size() != 1) {
                throw new IllegalStateException("Expected exactly one owner record for the selected rehearsal database");
            }
            entries.addAll(databaseOwners);
        }
        entries.addAll(queryStrings(dataSource, """
                SELECT concat_ws('|', 'SCHEMA_OWNER', nspname, pg_get_userbyid(nspowner))
                FROM pg_namespace WHERE nspname = 'public'
                """));
        entries.addAll(queryStrings(dataSource, """
                SELECT concat_ws('|', 'OBJECT_OWNER', relation.relname, relation.relkind::text,
                    pg_get_userbyid(relation.relowner))
                FROM pg_class relation
                JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
                WHERE schema_row.nspname = 'public'
                    AND relation.relkind IN ('r', 'p', 'S', 'v', 'm', 'f', 'i', 'I')
                ORDER BY relation.relname COLLATE "C"
                """));
        entries.sort(String::compareTo);
        return entries;
    }

    private static List<String> queryStrings(DataSource dataSource, String sql, Object... args) {
        return new JdbcTemplate(dataSource).queryForList(sql, String.class, args);
    }

    private static Map<String, TableDigest> tableDigests(DataSource dataSource) {
        List<String> tables = queryStrings(dataSource, """
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                ORDER BY table_name COLLATE "C"
                """);
        Map<String, TableDigest> digests = new TreeMap<>();
        for (String table : tables) {
            List<String> rows = canonicalRows(dataSource, table);
            MessageDigest digest = sha256Digest();
            for (String row : rows) {
                byte[] bytes = row.getBytes(StandardCharsets.UTF_8);
                digest.update((byte) (bytes.length >>> 24));
                digest.update((byte) (bytes.length >>> 16));
                digest.update((byte) (bytes.length >>> 8));
                digest.update((byte) bytes.length);
                digest.update(bytes);
            }
            digests.put(table, new TableDigest(rows.size(), HexFormat.of().formatHex(digest.digest())));
        }
        return Map.copyOf(digests);
    }

    private static List<String> canonicalRows(DataSource dataSource, String tableName) {
        String sql = "SELECT to_jsonb(row_data)::text FROM public." + quoteIdentifier(tableName) +
                " AS row_data ORDER BY (to_jsonb(row_data)::text) COLLATE \"C\"";
        try (Connection connection = dataSource.getConnection(); Statement settings = connection.createStatement()) {
            settings.execute("SET TIME ZONE 'UTC'");
            settings.execute("SET DateStyle TO 'ISO, YMD'");
            try (PreparedStatement statement = connection.prepareStatement(sql); ResultSet rows = statement.executeQuery()) {
                List<String> canonicalRows = new ArrayList<>();
                while (rows.next()) {
                    canonicalRows.add(rows.getString(1));
                }
                return canonicalRows;
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Could not compute canonical fixture digest for a public table", exception);
        }
    }

    private static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static List<String> flywayHistory(DataSource dataSource) {
        return canonicalRowsForQuery(dataSource, """
                SELECT to_jsonb(history)::text FROM public.flyway_schema_history history
                ORDER BY history.installed_rank
                """);
    }

    private static List<String> canonicalRowsForQuery(DataSource dataSource, String sql) {
        try (Connection connection = dataSource.getConnection(); Statement settings = connection.createStatement()) {
            settings.execute("SET TIME ZONE 'UTC'");
            settings.execute("SET DateStyle TO 'ISO, YMD'");
            try (PreparedStatement statement = connection.prepareStatement(sql); ResultSet rows = statement.executeQuery()) {
                List<String> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(rows.getString(1));
                }
                return List.copyOf(result);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Could not read canonical database metadata", exception);
        }
    }

    private static Map<String, SequenceState> sequenceStates(DataSource dataSource) {
        List<String> names = queryStrings(dataSource, """
                SELECT sequence_name FROM information_schema.sequences
                WHERE sequence_schema = 'public'
                ORDER BY sequence_name COLLATE "C"
                """);
        Map<String, SequenceState> states = new TreeMap<>();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        for (String name : names) {
            Map<String, Object> row = jdbc.queryForMap("SELECT last_value, is_called FROM public." +
                    quoteIdentifier(name));
            states.put(name, new SequenceState(((Number) row.get("last_value")).longValue(),
                    (Boolean) row.get("is_called")));
        }
        return Map.copyOf(states);
    }

    private static List<String> normalizedPrivileges(DataSource dataSource, String databaseName) {
        List<String> result = new ArrayList<>();
        result.addAll(queryStrings(dataSource, """
                SELECT concat_ws('|', 'DATABASE', role_name,
                    has_database_privilege(role_name, ?, 'CONNECT')::text)
                FROM (VALUES (?), (?), (?))
                    AS roles(role_name)
                ORDER BY role_name COLLATE "C"
                """, databaseName, APPLICATION_USER, BACKUP_USER, MIGRATION_USER));
        result.addAll(queryStrings(dataSource, """
                SELECT concat_ws('|', 'SCHEMA', role_name,
                    has_schema_privilege(role_name, 'public', 'USAGE')::text,
                    has_schema_privilege(role_name, 'public', 'CREATE')::text)
                FROM (VALUES (?), (?), (?))
                    AS roles(role_name)
                ORDER BY role_name COLLATE "C"
                """, APPLICATION_USER, BACKUP_USER, MIGRATION_USER));
        result.addAll(queryStrings(dataSource, """
                SELECT concat_ws('|', 'TABLE', role_name, table_name,
                    has_table_privilege(role_name, table_name, 'SELECT')::text,
                    has_table_privilege(role_name, table_name, 'INSERT')::text,
                    has_table_privilege(role_name, table_name, 'UPDATE')::text,
                    has_table_privilege(role_name, table_name, 'DELETE')::text)
                FROM (VALUES
                    (?, ?), (?, ?), (?, ?), (?, ?), (?, ?),
                    (?, ?), (?, ?), (?, ?), (?, ?))
                    AS privileges(role_name, table_name)
                ORDER BY role_name COLLATE "C", table_name COLLATE "C"
                """, APPLICATION_USER, "public.colors", APPLICATION_USER, "public.material_colors",
                APPLICATION_USER, "public.model_3d", APPLICATION_USER, "public.media_asset",
                APPLICATION_USER, "public.flyway_schema_history", BACKUP_USER, "public.model_3d",
                BACKUP_USER, "public.media_asset", BACKUP_USER, "public.flyway_schema_history",
                MIGRATION_USER, "public.flyway_schema_history"));
        result.addAll(queryStrings(dataSource, """
                SELECT concat_ws('|', 'SEQUENCE', role_name,
                    has_sequence_privilege(role_name, 'public.colors_color_id_seq', 'USAGE')::text,
                    has_sequence_privilege(role_name, 'public.colors_color_id_seq', 'SELECT')::text,
                    has_sequence_privilege(role_name, 'public.colors_color_id_seq', 'UPDATE')::text,
                    has_sequence_privilege(role_name, 'public.media_asset_id_seq', 'USAGE')::text,
                    has_sequence_privilege(role_name, 'public.media_asset_id_seq', 'SELECT')::text,
                    has_sequence_privilege(role_name, 'public.media_asset_id_seq', 'UPDATE')::text)
                FROM (VALUES (?), (?), (?))
                    AS roles(role_name)
                ORDER BY role_name COLLATE "C"
                """, APPLICATION_USER, BACKUP_USER, MIGRATION_USER));
        result.sort(String::compareTo);
        return List.copyOf(result);
    }

    private static void assertSnapshotsMatch(DatabaseSnapshot source, DatabaseSnapshot restored) {
        if (!source.schemaFingerprint().equals(restored.schemaFingerprint())) {
            throw new IllegalStateException(schemaMismatchDiagnostic(source, restored, "fingerprints differ"));
        }
        if (!source.schemaObjects().equals(restored.schemaObjects())) {
            throw new IllegalStateException(schemaMismatchDiagnostic(source, restored,
                    "descriptor lists differ despite matching fingerprints"));
        }
        if (!source.unverifiedExpectedChecks().isEmpty() || !restored.unverifiedExpectedChecks().isEmpty()) {
            throw new IllegalStateException(unverifiedCheckDiagnostic(source, restored));
        }
        if (!source.tableDigests().equals(restored.tableDigests())) {
            throw new IllegalStateException("Restored row counts or canonical table digests differ from source");
        }
        if (!source.flywayHistory().equals(restored.flywayHistory())) {
            throw new IllegalStateException("Restored Flyway history differs from backup source");
        }
        if (!source.sequenceStates().equals(restored.sequenceStates())) {
            throw new IllegalStateException("Restored sequence last_value/is_called differs from source");
        }
        if (!source.ownership().equals(restored.ownership())) {
            throw new IllegalStateException("Restored object ownership differs from backup source");
        }
        if (!source.privileges().equals(restored.privileges())) {
            throw new IllegalStateException("Restored role privileges differ from backup source");
        }
        if (!source.postgresVersion().equals(restored.postgresVersion()) ||
                !source.imageDigest().equals(restored.imageDigest())) {
            throw new IllegalStateException("Source and restore verification did not use the same pinned PostgreSQL");
        }
    }

    private static String schemaMismatchDiagnostic(DatabaseSnapshot source, DatabaseSnapshot restored,
                                                   String mismatchReason) {
        Map<String, Integer> sourceCounts = descriptorCounts(source.schemaObjects());
        Map<String, Integer> restoredCounts = descriptorCounts(restored.schemaObjects());
        Map<String, Integer> sourceOnly = new TreeMap<>();
        Map<String, Integer> restoredOnly = new TreeMap<>();
        TreeSet<String> descriptors = new TreeSet<>();
        descriptors.addAll(sourceCounts.keySet());
        descriptors.addAll(restoredCounts.keySet());
        for (String descriptor : descriptors) {
            int difference = sourceCounts.getOrDefault(descriptor, 0) - restoredCounts.getOrDefault(descriptor, 0);
            if (difference > 0) {
                sourceOnly.put(descriptor, difference);
            } else if (difference < 0) {
                restoredOnly.put(descriptor, -difference);
            }
        }

        StringBuilder diagnostic = new StringBuilder(1_500)
                .append("Restored schema ").append(mismatchReason)
                .append("; sourceFingerprint=").append(source.schemaFingerprint())
                .append("; restoredFingerprint=").append(restored.schemaFingerprint())
                .append("; descriptorCounts[source=").append(source.schemaObjects().size())
                .append(", restored=").append(restored.schemaObjects().size()).append("]")
                .append("; categoryDifferences[");
        boolean firstCategory = true;
        for (String category : SCHEMA_DESCRIPTOR_CATEGORIES) {
            if (!firstCategory) {
                diagnostic.append(", ");
            }
            firstCategory = false;
            diagnostic.append(category).append("{sourceOnly=")
                    .append(countCategory(sourceOnly, category)).append(", restoreOnly=")
                    .append(countCategory(restoredOnly, category)).append('}');
        }
        int unknownSource = countUnknownCategories(sourceOnly);
        int unknownRestored = countUnknownCategories(restoredOnly);
        if (unknownSource != 0 || unknownRestored != 0) {
            diagnostic.append(", UNKNOWN{sourceOnly=").append(unknownSource)
                    .append(", restoreOnly=").append(unknownRestored).append('}');
        }
        diagnostic.append(']');
        appendDescriptorDifferences(diagnostic, "sourceOnly", sourceOnly);
        appendDescriptorDifferences(diagnostic, "restoreOnly", restoredOnly);
        appendRawConstraintDifferences(diagnostic, "sourceRawConstraints", source.rawConstraintDescriptors(),
                sourceOnly);
        appendRawConstraintDifferences(diagnostic, "restoreRawConstraints", restored.rawConstraintDescriptors(),
                restoredOnly);

        String result = diagnostic.toString();
        if (result.length() > MAX_SCHEMA_DIAGNOSTIC_LENGTH) {
            String truncationMarker = "...[diagnostic truncated]";
            return result.substring(0, MAX_SCHEMA_DIAGNOSTIC_LENGTH - truncationMarker.length()) + truncationMarker;
        }
        return result;
    }

    private static String unverifiedCheckDiagnostic(DatabaseSnapshot source, DatabaseSnapshot restored) {
        StringBuilder diagnostic = new StringBuilder(
                "Expected migration CHECK contract was not verified; sourceOnly=");
        appendBoundedRawDescriptors(diagnostic, source.unverifiedExpectedChecks());
        diagnostic.append("; restoreOnly=");
        appendBoundedRawDescriptors(diagnostic, restored.unverifiedExpectedChecks());
        String result = diagnostic.toString();
        if (result.length() > MAX_SCHEMA_DIAGNOSTIC_LENGTH) {
            String marker = "...[diagnostic truncated]";
            return result.substring(0, MAX_SCHEMA_DIAGNOSTIC_LENGTH - marker.length()) + marker;
        }
        return result;
    }

    private static void appendBoundedRawDescriptors(StringBuilder diagnostic, List<String> descriptors) {
        diagnostic.append("count=").append(descriptors.size()).append(", examples=[");
        descriptors.stream().limit(MAX_SCHEMA_MISMATCHES_PER_SIDE).forEach(descriptor -> {
            String bounded = sanitizeSchemaDescriptor(descriptor);
            if (bounded.length() > MAX_SCHEMA_DESCRIPTOR_LENGTH) {
                bounded = bounded.substring(0, MAX_SCHEMA_DESCRIPTOR_LENGTH - 3) + "...";
            }
            diagnostic.append('{').append(bounded).append("}, ");
        });
        if (!descriptors.isEmpty()) {
            diagnostic.setLength(diagnostic.length() - 2);
        }
        diagnostic.append(']');
    }

    private static void appendRawConstraintDifferences(StringBuilder diagnostic, String label,
                                                       List<String> rawConstraints,
                                                       Map<String, Integer> differences) {
        List<Map.Entry<String, Integer>> changedConstraints = differences.entrySet().stream()
                .filter(entry -> "CONSTRAINT".equals(descriptorCategory(entry.getKey())))
                .limit(MAX_SCHEMA_MISMATCHES_PER_SIDE)
                .toList();
        diagnostic.append("; ").append(label).append("[showing=").append(changedConstraints.size()).append(']');
        for (Map.Entry<String, Integer> difference : changedConstraints) {
            String[] parts = difference.getKey().split("\\|", 4);
            if (parts.length < 3) {
                continue;
            }
            String prefix = String.join("|", parts[0], parts[1], parts[2]) + "|";
            rawConstraints.stream().filter(raw -> raw.startsWith(prefix)).findFirst().ifPresent(raw -> {
                String bounded = sanitizeSchemaDescriptor(raw);
                if (bounded.length() > MAX_SCHEMA_DESCRIPTOR_LENGTH) {
                    bounded = bounded.substring(0, MAX_SCHEMA_DESCRIPTOR_LENGTH - 3) + "...";
                }
                diagnostic.append("\n  [x").append(difference.getValue()).append("] ").append(bounded);
            });
        }
    }

    private static Map<String, Integer> descriptorCounts(List<String> descriptors) {
        Map<String, Integer> counts = new TreeMap<>();
        for (String descriptor : descriptors) {
            counts.merge(descriptor, 1, Integer::sum);
        }
        return counts;
    }

    private static int countCategory(Map<String, Integer> differences, String category) {
        return differences.entrySet().stream()
                .filter(entry -> descriptorCategory(entry.getKey()).equals(category))
                .mapToInt(Map.Entry::getValue)
                .sum();
    }

    private static int countUnknownCategories(Map<String, Integer> differences) {
        return differences.entrySet().stream()
                .filter(entry -> !SCHEMA_DESCRIPTOR_CATEGORIES.contains(descriptorCategory(entry.getKey())))
                .mapToInt(Map.Entry::getValue)
                .sum();
    }

    private static String descriptorCategory(String descriptor) {
        int separator = descriptor.indexOf('|');
        return separator < 0 ? descriptor : descriptor.substring(0, separator);
    }

    private static void appendDescriptorDifferences(StringBuilder diagnostic, String label,
                                                    Map<String, Integer> differences) {
        int totalOccurrences = differences.values().stream().mapToInt(Integer::intValue).sum();
        diagnostic.append("; ").append(label).append("Descriptors[occurrences=")
                .append(totalOccurrences).append(", showing=")
                .append(Math.min(differences.size(), MAX_SCHEMA_MISMATCHES_PER_SIDE)).append("]");
        differences.entrySet().stream()
                .limit(MAX_SCHEMA_MISMATCHES_PER_SIDE)
                .forEach(entry -> {
                    String descriptor = sanitizeSchemaDescriptor(entry.getKey());
                    if (descriptor.length() > MAX_SCHEMA_DESCRIPTOR_LENGTH) {
                        descriptor = descriptor.substring(0, MAX_SCHEMA_DESCRIPTOR_LENGTH - 3) + "...";
                    }
                    diagnostic.append("\n  [x").append(entry.getValue()).append("] ").append(descriptor);
                });
    }

    private static String sanitizeSchemaDescriptor(String descriptor) {
        return descriptor.replaceAll("(?i)(password|secret|token|credential|api[_-]?key)(\\s*[=:]\\s*)[^,;\\s)]+",
                        "$1$2[REDACTED]")
                .replaceAll("[\\r\\n\\t]", " ");
    }

    private static void assertMigrationHistory(DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Integer baselines = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE type = 'BASELINE'", Integer.class);
        List<String> versions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history ORDER BY installed_rank", String.class);
        if (baselines == null || baselines != 0
                || !versions.equals(List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19", "20", "21"))) {
            throw new IllegalStateException("Restored Flyway history must contain successful V1 through V21 only");
        }
    }

    private static void assertMigrationRoleOwnsRestoredObjects(DataSource dataSource) {
        List<String> owners = queryStrings(dataSource, """
                SELECT pg_get_userbyid(relation.relowner)
                FROM pg_class relation
                JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
                WHERE schema_row.nspname = 'public'
                    AND relation.relkind IN ('r', 'p', 'S', 'v', 'm', 'f', 'i', 'I')
                """);
        if (owners.isEmpty() || owners.stream().anyMatch(owner ->
                !MIGRATION_USER.equals(owner) && !"payos_trust_owner".equals(owner)
                        && !"admin3d_owner".equals(owner) && !"admin3d_lock_owner".equals(owner)
                        && !"admin3d_command_owner".equals(owner))) {
            throw new IllegalStateException("Unexpected owner on restored table, index or sequence");
        }
        String databaseOwner = queryStrings(dataSource, """
                SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname = current_database()
                """).stream().findFirst().orElse("");
        if (!MIGRATION_USER.equals(databaseOwner)) {
            throw new IllegalStateException("Migration role does not own the restored database");
        }
    }

    private static void assertApplicationAndBackupPrivileges(DataSource dataSource, String databaseName) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        if (!privilege(jdbc, "SELECT has_database_privilege(?, ?, 'CONNECT')", APPLICATION_USER, databaseName) ||
                !privilege(jdbc, "SELECT has_schema_privilege(?, 'public', 'USAGE')", APPLICATION_USER) ||
                privilege(jdbc, "SELECT has_schema_privilege(?, 'public', 'CREATE')", APPLICATION_USER) ||
                !privilege(jdbc, "SELECT has_table_privilege(?, 'public.flyway_schema_history', 'SELECT')",
                        APPLICATION_USER)) {
            throw new IllegalStateException("Restored application role has incorrect database/schema privileges");
        }
        for (String operation : List.of("INSERT", "UPDATE", "DELETE")) {
            if (privilege(jdbc, "SELECT has_table_privilege(?, 'public.flyway_schema_history', '" + operation + "')",
                    APPLICATION_USER)) {
                throw new IllegalStateException("Restored application role can modify Flyway history");
            }
        }
        for (String table : List.of("colors", "material_colors", "model_3d", "model_images", "media_asset")) {
            for (String operation : List.of("SELECT", "INSERT", "UPDATE", "DELETE")) {
                if (!privilege(jdbc, "SELECT has_table_privilege(?, 'public." + table + "', '" + operation + "')",
                        APPLICATION_USER)) {
                    throw new IllegalStateException("Restored application role is missing required table access");
                }
            }
        }
        assertBackupRoleIsReadLimited(dataSource, databaseName);
    }

    private static void requirePostgresVersion(String version) {
        if (version == null || !version.startsWith("16.15")) {
            throw new IllegalStateException("Disposable rehearsal requires the approved PostgreSQL 16.15 image");
        }
    }

    private static void requireClientVersion(String version, String clientName) {
        if (version == null || !version.startsWith(clientName + " (PostgreSQL) 16.15")) {
            throw new IllegalStateException("PostgreSQL client must match the pinned 16.15 container version");
        }
    }

    private static FluentConfiguration flyway(DataSource dataSource) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas("public").defaultSchema("public").baselineOnMigrate(false);
    }

    private static String databaseUrl(String databaseName) {
        String jdbcUrl = POSTGRES.getJdbcUrl();
        int prefix = "jdbc:postgresql://".length();
        int path = jdbcUrl.indexOf('/', prefix);
        if (path < 0) {
            throw new IllegalStateException("Testcontainers returned an invalid PostgreSQL JDBC URL");
        }
        int query = jdbcUrl.indexOf('?', path);
        return jdbcUrl.substring(0, path + 1) + databaseName + (query < 0 ? "" : jdbcUrl.substring(query));
    }

    private static DataSource adminDataSource() {
        return new DriverManagerDataSource(databaseUrl("postgres"), ADMIN_USER, ADMIN_PASSWORD);
    }

    private static DataSource adminDataSource(String databaseName) {
        return new DriverManagerDataSource(databaseUrl(databaseName), ADMIN_USER, ADMIN_PASSWORD);
    }

    private static DataSource migrationDataSource(String databaseName) {
        return new DriverManagerDataSource(databaseUrl(databaseName), MIGRATION_USER, MIGRATION_PASSWORD);
    }

    private static String queryString(DataSource dataSource, String sql) {
        return new JdbcTemplate(dataSource).queryForObject(sql, String.class);
    }

    private static int count(DataSource dataSource, String sql) {
        Integer value = new JdbcTemplate(dataSource).queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    private static String sha256(String value) {
        return HexFormat.of().formatHex(sha256Digest().digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String newPassword() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    @Test
    void restoredSchemaDataPrivilegesAndFlywayHistoryMatchTheBackupSource() {
        assertThat(rehearsalPrepared).isTrue();
        assertThat(POSTGRES_IMAGE_DIGEST).isEqualTo(sourceImageDigest);
        assertThat(sourceVersion).startsWith("16.15");
        assertThat(baselineHistoryRows).isZero();
        assertThat(fixtureIds).isNotNull();
        assertThat(fixtureIds.modelAssetId()).isNotNull();
        assertThat(fixtureIds.imageAssetId()).isNotNull();
        assertThat(archiveListing).contains("flyway_schema_history", "materials", "material_stock_logs",
                "model_3d", "model_images", "media_asset", "SEQUENCE SET", "ACL");
        assertThat(pgDumpVersion).startsWith("pg_dump (PostgreSQL) 16.15");
        assertThat(pgRestoreVersion).startsWith("pg_restore (PostgreSQL) 16.15");
        assertThat(restoredSnapshot.schemaFingerprint()).isEqualTo(sourceSnapshot.schemaFingerprint());
        assertThat(restoredSnapshot.schemaObjects()).isEqualTo(sourceSnapshot.schemaObjects());
        assertThat(restoredSnapshot.tableDigests()).isEqualTo(sourceSnapshot.tableDigests());
        assertThat(restoredSnapshot.flywayHistory()).isEqualTo(sourceSnapshot.flywayHistory());
        assertThat(restoredSnapshot.sequenceStates()).isEqualTo(sourceSnapshot.sequenceStates());
        assertThat(restoredSnapshot.ownership()).isEqualTo(sourceSnapshot.ownership());
        assertThat(restoredSnapshot.privileges()).isEqualTo(sourceSnapshot.privileges());
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(environment.getProperty("spring.flyway.enabled", Boolean.class)).isFalse();
        SessionFactoryImplementor sessionFactory = entityManagerFactory.unwrap(SessionFactoryImplementor.class);
        assertThat(sessionFactory.getJdbcServices().getDialect()).isInstanceOf(PostgreSQLDialect.class);
        assertThat(sha256(String.join("\n",
                schemaDescriptors(migrationDataSource(RESTORE_DATABASE)).descriptors())))
                .isEqualTo(restoredSchemaFingerprintBeforeSpring);
        assertThat(applicationJdbc.queryForObject(
                "SELECT file_path_3d FROM model_3d WHERE model_id = ?", String.class, fixtureIds.modelId()))
                .isEqualTo(LEGACY_MODEL_PATH);
        assertThat(applicationJdbc.queryForObject(
                "SELECT image_path FROM model_images WHERE image_id = ?", String.class, fixtureIds.imageId()))
                .isEqualTo(LEGACY_IMAGE_PATH);
        assertThat(applicationJdbc.queryForObject(
                "SELECT COUNT(*) FROM material_stock_logs WHERE material_id = ?", Integer.class,
                fixtureIds.materialId())).isEqualTo(1);
    }

    @Test
    void restoredApplicationRoleCanReadAndUseCrudWithoutChangingFlywayOrSchemaPrivileges() {
        assertThat(applicationJdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history", Integer.class))
                .isEqualTo(21);
        String colorName = "R-" + UUID.randomUUID();
        Integer generatedId = applicationJdbc.queryForObject(
                "INSERT INTO colors (name, hex_code) VALUES (?, '#654321') RETURNING color_id",
                Integer.class, colorName);
        assertThat(generatedId).isNotNull();
        applicationJdbc.update("UPDATE colors SET name = ? WHERE color_id = ?", colorName + " updated", generatedId);
        assertThat(applicationJdbc.queryForObject(
                "SELECT name FROM colors WHERE color_id = ?", String.class, generatedId))
                .isEqualTo(colorName + " updated");
        assertThat(applicationJdbc.update("DELETE FROM colors WHERE color_id = ?", generatedId)).isEqualTo(1);

        assertThatThrownBy(() -> applicationJdbc.update(
                "UPDATE flyway_schema_history SET success = success WHERE installed_rank = 1"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> applicationJdbc.execute(
                "CREATE TABLE public.restore_application_must_not_create (id INTEGER)"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void restoredApplicationCanPersistGeneratedIdentityAndCompositeKeyRows() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            Color color = Color.builder()
                    .name("C-" + UUID.randomUUID())
                    .hexCode("#2468AC")
                    .build();
            entityManager.persist(color);
            entityManager.flush();
            assertThat(color.getId()).isNotNull();

            Material material = entityManager.find(Material.class, fixtureIds.materialId());
            assertThat(material).isNotNull();
            MaterialColor materialColor = MaterialColor.builder()
                    .id(new MaterialColorId(material.getId(), color.getId()))
                    .material(material)
                    .color(color)
                    .quantity(7.5d)
                    .build();
            entityManager.persist(materialColor);
            entityManager.flush();
            entityManager.clear();

            MaterialColor restored = entityManager.find(MaterialColor.class,
                    new MaterialColorId(material.getId(), color.getId()));
            assertThat(restored).isNotNull();
            assertThat(restored.getQuantity()).isEqualTo(7.5d);
        });
    }

    private record FixtureIds(Integer userId, Integer materialId, Integer colorId, Integer modelId, Integer imageId,
                              Long modelAssetId, Long imageAssetId) {
    }

    private record TableDigest(long rowCount, String sha256) {
    }

    private record SequenceState(long lastValue, boolean isCalled) {
    }

    private record SchemaDescriptorSnapshot(List<String> descriptors, List<String> rawConstraintDescriptors,
                                           List<String> unverifiedExpectedChecks) {
    }

    private record DatabaseSnapshot(String schemaFingerprint, List<String> schemaObjects,
                                    List<String> rawConstraintDescriptors, List<String> unverifiedExpectedChecks,
                                    Map<String, TableDigest> tableDigests,
                                    List<String> flywayHistory, Map<String, SequenceState> sequenceStates,
                                    List<String> ownership, List<String> privileges, String postgresVersion,
                                    String imageDigest) {
    }

}
