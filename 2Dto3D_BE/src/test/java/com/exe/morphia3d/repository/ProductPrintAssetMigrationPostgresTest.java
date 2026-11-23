package com.exe.morphia3d.repository;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class ProductPrintAssetMigrationPostgresTest {

    private static final String BOOTSTRAP_PASSWORD = "print-assets-test-only";
    private static final String MIGRATOR_PASSWORD = "print-assets-migrator-test-only";
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("print_assets_migration_test")
            .withUsername("print_assets_test")
            .withPassword(BOOTSTRAP_PASSWORD);

    private static JdbcTemplate bootstrap;
    private static Map<String, Integer> checksumsBeforeV21;

    @BeforeAll
    static void migrateThroughV20InDisposablePostgres() {
        POSTGRES.start();
        bootstrap = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        createRestrictedRoles();
        Flyway baseline = flyway("14");
        baseline.migrate();
        bootstrap.execute("GRANT REFERENCES ON TABLE public.model_3d, public.media_asset, public.materials, public.users TO admin3d_owner");
        Flyway throughV20 = flyway("20");
        throughV20.migrate();
        checksumsBeforeV21 = checksums(throughV20.info().applied());
        assertThat(checksumsBeforeV21).hasSize(20);
    }

    @Test
    void v21AddsAppendOnlyProductPrintAssetsWithoutChangingExistingSecurityBoundaries() {
        Flyway candidate = flyway(null);
        candidate.migrate();
        candidate.validate();

        assertThat(candidate.info().applied()).anySatisfy(migration ->
                assertThat(migration.getVersion().getVersion()).isEqualTo("21"));
        assertThat(checksums(candidate.info().applied())).containsAllEntriesOf(checksumsBeforeV21);

        assertThat(bootstrap.queryForObject("""
                SELECT pg_get_userbyid(c.relowner) = 'admin3d_owner'
                  FROM pg_catalog.pg_class c
                  JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = 'public' AND c.relname = 'product_print_assets'
                """, Boolean.class)).isTrue();
        assertThat(bootstrap.queryForObject("""
                SELECT count(*) = 5
                  FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = 'product_print_assets'
                   AND column_name IN ('product_id', 'media_asset_id', 'original_file_name', 'uploaded_by', 'uploaded_at')
                """, Boolean.class)).isTrue();
        assertThat(bootstrap.queryForObject("""
                SELECT count(*) = 3
                  FROM pg_catalog.pg_constraint c
                  JOIN pg_catalog.pg_class t ON t.oid = c.conrelid
                 WHERE t.relname = 'product_print_assets' AND c.contype = 'f'
                """, Boolean.class)).isTrue();

        assertThat(privilege("SELECT")).isTrue();
        assertThat(privilege("INSERT")).isTrue();
        assertThat(privilege("UPDATE")).isFalse();
        assertThat(privilege("DELETE")).isFalse();
        assertThat(privilege("TRUNCATE")).isFalse();
        assertThat(bootstrap.queryForObject("SELECT to_regclass('public.product_print_assets_id_seq') IS NULL", Boolean.class))
                .isTrue();
        assertThat(bootstrap.queryForObject("SELECT pg_catalog.pg_has_role('app_runtime', 'admin3d_owner', 'MEMBER')", Boolean.class))
                .isFalse();
        assertThat(bootstrap.queryForObject("SELECT pg_catalog.pg_has_role('app_runtime', 'admin3d_command_owner', 'MEMBER')", Boolean.class))
                .isFalse();
        assertThat(bootstrap.queryForObject("SELECT pg_catalog.pg_has_role('app_runtime', 'payos_trust_owner', 'MEMBER')", Boolean.class))
                .isFalse();
        assertThat(bootstrap.queryForObject("SELECT has_table_privilege('app_runtime', 'public.payos_merchant_bindings', 'SELECT')",
                Boolean.class)).isFalse();
        assertThat(bootstrap.queryForObject("SELECT has_sequence_privilege('app_runtime', 'public.payos_order_code_seq', 'USAGE')",
                Boolean.class)).isFalse();
        assertThat(ownerOf("admin3d_publish_designer_model")).isEqualTo("admin3d_command_owner");
        assertThat(ownerOf("payos_create_live_payment_link")).isEqualTo("payos_trust_owner");
    }

    private static Flyway flyway(String target) {
        var configuration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), "admin3d_migrator", MIGRATOR_PASSWORD)
                .locations("classpath:db/migration");
        if (target != null) configuration.target(MigrationVersion.fromVersion(target));
        return configuration.load();
    }

    private static Map<String, Integer> checksums(org.flywaydb.core.api.MigrationInfo[] migrations) {
        Map<String, Integer> result = new TreeMap<>();
        for (var migration : migrations) {
            if (migration.getVersion() != null && migration.getState().isApplied()
                    && migration.getVersion().compareTo(MigrationVersion.fromVersion("20")) <= 0) {
                result.put(migration.getVersion().getVersion(), migration.getChecksum());
            }
        }
        return result;
    }

    private static boolean privilege(String privilege) {
        return Boolean.TRUE.equals(bootstrap.queryForObject(
                "SELECT has_table_privilege('app_runtime', 'public.product_print_assets', ?)",
                Boolean.class, privilege));
    }

    private static String ownerOf(String functionName) {
        return bootstrap.queryForObject("""
                SELECT pg_catalog.pg_get_userbyid(p.proowner)
                  FROM pg_catalog.pg_proc p
                  JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
                 WHERE n.nspname = 'public' AND p.proname = ?
                 ORDER BY p.oid LIMIT 1
                """, String.class, functionName);
    }

    private static void createRestrictedRoles() {
        for (String role : new String[]{"admin3d_owner", "admin3d_lock_owner", "admin3d_command_owner", "payos_trust_owner"}) {
            bootstrap.execute("CREATE ROLE " + role + " NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
        }
        bootstrap.execute("CREATE ROLE admin3d_migrator LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '"
                + MIGRATOR_PASSWORD + "'");
        bootstrap.execute("CREATE ROLE app_runtime NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
        bootstrap.execute("GRANT admin3d_owner, admin3d_lock_owner, admin3d_command_owner, payos_trust_owner TO admin3d_migrator WITH INHERIT FALSE, SET TRUE");
        bootstrap.execute("GRANT USAGE, CREATE ON SCHEMA public TO admin3d_owner, admin3d_lock_owner, admin3d_command_owner, payos_trust_owner");
        bootstrap.execute("GRANT CREATE ON SCHEMA public TO admin3d_migrator");
    }
}
