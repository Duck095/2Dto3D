package com.exe.morphia3d.repository;

import com.exe.morphia3d.service.MediaAssetReconciler;
import com.exe.morphia3d.test.support.PostgresTestRoleProvisioning;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Checks runtime access from the same migrations used by a fresh application database. */
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
class AppRuntimePrivilegeMigrationPostgresTest {
    private static final String MIGRATOR_PASSWORD = "runtime_migrator_test_only";
    private static final String RUNTIME_PASSWORD = "runtime_app_test_only";
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("runtime_privileges")
            .withUsername("runtime_privileges_admin")
            .withPassword("runtime_privileges_test_only");

    private static JdbcTemplate bootstrap;
    @Autowired private WebApplicationContext context;
    @Autowired private MediaAssetReconciler reconciler;
    private MockMvc mvc;

    @BeforeAll
    static void migrateFreshDatabase() {
        POSTGRES.start();
        bootstrap = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        bootstrap.execute("CREATE ROLE admin3d_migrator LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '"
                + MIGRATOR_PASSWORD + "'");
        PostgresTestRoleProvisioning.provisionMigrationRoles(bootstrap, "admin3d_migrator");
        bootstrap.execute("GRANT USAGE, CREATE ON SCHEMA public TO admin3d_migrator WITH GRANT OPTION");
        bootstrap.execute("ALTER ROLE app_runtime LOGIN PASSWORD '" + RUNTIME_PASSWORD + "'");
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), "admin3d_migrator", MIGRATOR_PASSWORD)
                .locations("classpath:db/migration").target("14").load().migrate();
        PostgresTestRoleProvisioning.grantSnapshotReferencePrivileges(bootstrap);
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), "admin3d_migrator", MIGRATOR_PASSWORD)
                .locations("classpath:db/migration").load().migrate();
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), "admin3d_migrator", MIGRATOR_PASSWORD)
                .locations("classpath:db/migration").load().validate();
    }

    @DynamicPropertySource
    static void configureRuntimeDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "app_runtime");
        registry.add("spring.datasource.password", () -> RUNTIME_PASSWORD);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @BeforeEach
    void setUpMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void runtimeGetsMediaAndCatalogPrivilegesWithoutCrossingProductSecurityBoundary() {
        assertPrivilege("public.media_asset", "SELECT", true);
        assertPrivilege("public.media_asset", "INSERT", true);
        assertPrivilege("public.media_asset", "UPDATE", false);
        assertPrivilege("public.materials", "SELECT", true);
        assertPrivilege("public.materials", "UPDATE", false);
        assertPrivilege("public.colors", "INSERT", true);
        assertPrivilege("public.colors", "DELETE", false);
        assertPrivilege("public.material_colors", "DELETE", true);
        assertPrivilege("public.media_asset", "DELETE", false);

        assertPrivilege("public.model_3d", "UPDATE", false);
        assertPrivilege("public.admin3d_publication_policy", "SELECT", false);
        assertThat(bootstrap.queryForObject(
                "SELECT has_schema_privilege('app_runtime', 'public', 'CREATE')", Boolean.class)).isFalse();
        assertThat(bootstrap.queryForObject(
                "SELECT has_column_privilege('app_runtime', 'public.materials', 'name', 'UPDATE')", Boolean.class)).isTrue();
        assertThat(bootstrap.queryForObject(
                "SELECT has_column_privilege('app_runtime', 'public.materials', 'material_id', 'UPDATE')", Boolean.class)).isFalse();
        assertThat(bootstrap.queryForObject(
                "SELECT has_column_privilege('app_runtime', 'public.media_asset', 'state', 'UPDATE')", Boolean.class)).isTrue();
        assertThat(bootstrap.queryForObject("""
                SELECT NOT has_column_privilege('app_runtime', 'public.media_asset', 'id', 'UPDATE')
                   AND NOT has_column_privilege('app_runtime', 'public.media_asset', 'operation_id', 'UPDATE')
                   AND NOT has_column_privilege('app_runtime', 'public.media_asset', 'created_at', 'UPDATE')
                """, Boolean.class)).isTrue();
        assertThat(bootstrap.queryForObject(
                "SELECT has_table_privilege('app_runtime', 'public.payos_payment_links', 'SELECT')", Boolean.class)).isFalse();
        assertThat(bootstrap.queryForObject(
                "SELECT has_sequence_privilege('app_runtime', 'public.payos_order_code_seq', 'USAGE')", Boolean.class)).isFalse();
        assertThat(bootstrap.queryForObject(
                "SELECT pg_has_role('app_runtime', 'admin3d_command_owner', 'MEMBER')", Boolean.class)).isFalse();
        assertThat(bootstrap.queryForObject("""
                SELECT NOT pg_has_role('app_runtime', 'admin3d_owner', 'MEMBER')
                   AND NOT pg_has_role('app_runtime', 'admin3d_lock_owner', 'MEMBER')
                   AND NOT pg_has_role('app_runtime', 'payos_trust_owner', 'MEMBER')
                """, Boolean.class)).isTrue();
    }

    @Test
    void runtimeDatasourceCanServeMaterialAndColorManagementApis() throws Exception {
        assertThat(reconciler.reconcileOnce()).isZero();

        mvc.perform(post("/api/colors/create")
                        .with(user("catalog-user").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Forbidden Runtime Color\",\"hexCode\":\"#010203\"}"))
                .andExpect(status().isForbidden());
        assertThat(bootstrap.queryForObject(
                "SELECT count(*) FROM colors WHERE name = 'Forbidden Runtime Color'", Integer.class)).isZero();

        mvc.perform(post("/api/colors/create")
                        .with(user("catalog-admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Runtime Violet\",\"hexCode\":\"#663399\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/colors/Runtime Violet"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/materials/create")
                        .with(user("catalog-admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Runtime PLA\",\"type\":\"PLA\",\"pricePerGram\":0.05,\"stockQuantity\":100,\"unit\":\"g\",\"colors\":[]}"))
                .andExpect(status().isOk());
        Integer materialId = bootstrap.queryForObject(
                "SELECT material_id FROM materials WHERE name = 'Runtime PLA'", Integer.class);
        mvc.perform(put("/api/materials/{id}", materialId)
                        .with(user("catalog-admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Runtime PETG\",\"type\":\"PETG\",\"pricePerGram\":0.06,\"stockQuantity\":75,\"unit\":\"g\",\"colors\":[]}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/materials/"))
                .andExpect(status().isOk());
        assertThat(bootstrap.queryForObject(
                "SELECT name FROM materials WHERE material_id = ?", String.class, materialId))
                .isEqualTo("Runtime PETG");
    }

    private void assertPrivilege(String table, String privilege, boolean expected) {
        assertThat(bootstrap.queryForObject(
                "SELECT has_table_privilege('app_runtime', ?, ?)", Boolean.class, table, privilege))
                .as("app_runtime %s on %s", privilege, table).isEqualTo(expected);
    }
}
