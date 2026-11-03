package com.exe.morphia3d.repository;

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
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Explicitly selected, read-only validation of the local migrated PostgreSQL schema.
 * This test deliberately does not start the Spring application or any external integration.
 */
class LocalPostgresHibernateSchemaValidationIT {

    private static final String OPT_IN_ENV = "STAGING_HIBERNATE_VALIDATE_ENABLED";
    private static final String JDBC_URL_ENV = "STAGING_PG_URL";
    private static final String PASSWORD_ENV = "STAGING_PG_PASSWORD";
    private static final String REQUIRED_OPT_IN = "true";
    private static final String REQUIRED_JDBC_URL =
            "jdbc:postgresql://127.0.0.1:15432/morphia3d_staging_test";
    private static final String REQUIRED_DATABASE = "morphia3d_staging_test";
    private static final String REQUIRED_USERNAME = "morphia3d_app";
    private static final String REQUIRED_POSTGRES_MAJOR = "16";
    private static final String APPLICATION_SCHEMA = "public";
    private static final int REQUIRED_APPLICATION_TABLES = 18;
    private static final int REQUIRED_APPLICATION_SEQUENCES = 17;

    private static final List<Class<?>> MAPPED_TYPES = List.of(
            AIGenerationSession.class,
            Cart.class,
            CartItem.class,
            Color.class,
            Feedback.class,
            Material.class,
            MaterialColor.class,
            MaterialStockLog.class,
            MediaAsset.class,
            Model3d.class,
            ModelImage.class,
            ModelPart.class,
            Order.class,
            OrderItem.class,
            Payment.class,
            PriceEstimation.class,
            Printer.class,
            User.class,
            MaterialColorId.class
    );

    @Test
    void validatesAllApplicationMappingsWithoutChangingTheLocalSchema() throws Exception {
        String password = validateRuntimeConfiguration();
        HikariDataSource dataSource = null;
        StandardServiceRegistry registry = null;
        SessionFactory sessionFactory = null;
        SchemaSnapshot before = null;
        SchemaSnapshot after = null;
        Throwable failure = null;
        boolean validationAttempted = false;

        try {
            dataSource = createReadOnlyDataSource(password);
            verifyDatabaseIdentityAndReadOnly(dataSource);
            before = captureSchema(dataSource);
            assertThat(before.applicationTableCount())
                    .as("application tables visible to %s in schema %s", REQUIRED_USERNAME, APPLICATION_SCHEMA)
                    .isEqualTo(REQUIRED_APPLICATION_TABLES);
            assertThat(before.applicationSequenceCount())
                    .as("application sequences visible to %s in schema %s", REQUIRED_USERNAME, APPLICATION_SCHEMA)
                    .isEqualTo(REQUIRED_APPLICATION_SEQUENCES);

            validationAttempted = true;
            registry = createHibernateRegistry(dataSource);
            MetadataSources metadataSources = new MetadataSources(registry);
            for (Class<?> mappedType : MAPPED_TYPES) {
                metadataSources.addAnnotatedClass(mappedType);
            }
            sessionFactory = metadataSources.buildMetadata().buildSessionFactory();
            assertExpectedManagedTypes(sessionFactory);
        } catch (Throwable validationOrSetupFailure) {
            failure = validationOrSetupFailure;
        } finally {
            failure = closeSessionFactory(sessionFactory, failure);
            failure = destroyRegistry(registry, failure);

            if (dataSource != null) {
                if (validationAttempted) {
                    try {
                        verifyDatabaseIdentityAndReadOnly(dataSource);
                        after = captureSchema(dataSource);
                    } catch (Throwable afterValidationSnapshotFailure) {
                        failure = addFailure(failure, afterValidationSnapshotFailure);
                    }
                }

                if (before != null && after != null) {
                    try {
                        assertSnapshotsUnchanged(before, after);
                    } catch (Throwable schemaComparisonFailure) {
                        failure = addFailure(failure, schemaComparisonFailure);
                    }
                }

                failure = closeDataSource(dataSource, failure);
            }
        }

        if (failure != null) {
            AssertionError reported = new AssertionError(
                    "Local PostgreSQL Hibernate schema validation did not complete safely; see the cause and suppressed cleanup failures.");
            reported.initCause(failure);
            throw reported;
        }

        assertThat(sessionFactory).as("Hibernate SessionFactory built with hbm2ddl validate").isNotNull();
        assertThat(before).isNotNull();
        assertThat(after).isNotNull();
    }

    private static void assertExpectedManagedTypes(SessionFactory sessionFactory) {
        Set<Class<?>> actualEntities = new HashSet<>();
        sessionFactory.getMetamodel().getEntities()
                .forEach(entityType -> actualEntities.add(entityType.getJavaType()));
        assertThat(actualEntities).containsExactlyInAnyOrder(
                AIGenerationSession.class,
                Cart.class,
                CartItem.class,
                Color.class,
                Feedback.class,
                Material.class,
                MaterialColor.class,
                MaterialStockLog.class,
                MediaAsset.class,
                Model3d.class,
                ModelImage.class,
                ModelPart.class,
                Order.class,
                OrderItem.class,
                Payment.class,
                PriceEstimation.class,
                Printer.class,
                User.class);

        boolean embeddedIdRegistered = sessionFactory.getMetamodel().getManagedTypes().stream()
                .anyMatch(managedType -> managedType.getJavaType().equals(MaterialColorId.class));
        assertThat(embeddedIdRegistered).as("MaterialColorId embedded mapping registration").isTrue();
    }

    private static String validateRuntimeConfiguration() {
        String optIn = System.getenv(OPT_IN_ENV);
        if (!REQUIRED_OPT_IN.equals(optIn)) {
            throw new IllegalStateException("Explicit opt-in is required: set " + OPT_IN_ENV + "=true.");
        }

        String configuredUrl = System.getenv(JDBC_URL_ENV);
        if (!REQUIRED_JDBC_URL.equals(configuredUrl)) {
            throw new IllegalStateException(JDBC_URL_ENV + " must exactly match the approved loopback test database endpoint.");
        }

        String password = System.getenv(PASSWORD_ENV);
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("Protected runtime credential " + PASSWORD_ENV + " is required.");
        }
        return password;
    }

    private static HikariDataSource createReadOnlyDataSource(String password) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("local-postgres-hibernate-validation");
        config.setDriverClassName("org.postgresql.Driver");
        config.setJdbcUrl(REQUIRED_JDBC_URL);
        config.setUsername(REQUIRED_USERNAME);
        config.setPassword(password);
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(0);
        config.setReadOnly(true);
        config.setConnectionInitSql("SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY");
        config.setConnectionTimeout(5_000L);
        config.setValidationTimeout(3_000L);
        config.setInitializationFailTimeout(10_000L);
        config.setRegisterMbeans(false);
        return new HikariDataSource(config);
    }

    private static StandardServiceRegistry createHibernateRegistry(DataSource dataSource) {
        return new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.datasource", dataSource)
                .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                .applySetting("hibernate.hbm2ddl.auto", "validate")
                .applySetting("hibernate.implicit_naming_strategy",
                        "org.springframework.boot.hibernate.SpringImplicitNamingStrategy")
                .applySetting("hibernate.physical_naming_strategy",
                        "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl")
                .applySetting("hibernate.show_sql", "false")
                .applySetting("hibernate.format_sql", "false")
                .applySetting("hibernate.highlight_sql", "false")
                .applySetting("hibernate.generate_statistics", "false")
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", "true")
                .build();
    }

    private static void verifyDatabaseIdentityAndReadOnly(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.isReadOnly()).as("Hikari connection read-only flag").isTrue();
            connection.setAutoCommit(false);
            Throwable failure = null;
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT current_database(), current_user, current_setting('server_version'), current_setting('transaction_read_only')")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).as("connected database").isEqualTo(REQUIRED_DATABASE);
                assertThat(result.getString(2)).as("connected application role").isEqualTo(REQUIRED_USERNAME);
                assertThat(result.getString(3)).as("PostgreSQL server version").isNotBlank();
                assertThat(result.getString(3)).as("approved PostgreSQL major version")
                        .startsWith(REQUIRED_POSTGRES_MAJOR + ".");
                assertThat(result.getString(4)).as("actual PostgreSQL transaction read-only state").isEqualTo("on");
            } catch (Throwable identityFailure) {
                failure = identityFailure;
            }
            try {
                connection.rollback();
            } catch (Throwable rollbackFailure) {
                failure = addFailure(failure, rollbackFailure);
            }
            rethrowDatabaseCheckFailure(failure);
        }
    }

    private static SchemaSnapshot captureSchema(DataSource dataSource) throws SQLException {
        List<String> descriptors = new ArrayList<>();
        int tableCount;
        int sequenceCount;
        try (Connection connection = dataSource.getConnection()) {
            assertReadOnlyTransaction(connection);
            try {
                tableCount = appendQuery(connection, descriptors, "TABLE", """
                        SELECT c.relname
                        FROM pg_catalog.pg_class c
                        JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = 'public'
                          AND c.relkind IN ('r', 'p')
                          AND c.relname <> 'flyway_schema_history'
                        ORDER BY c.relname
                        """);

                appendQuery(connection, descriptors, "COLUMN", """
                        SELECT table_name, column_name, ordinal_position, data_type, udt_name,
                               character_maximum_length, numeric_precision, numeric_scale,
                               is_nullable, column_default, is_identity, identity_generation
                        FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND table_name <> 'flyway_schema_history'
                        ORDER BY table_name, ordinal_position
                        """);

                appendQuery(connection, descriptors, "CONSTRAINT", """
                        SELECT t.relname, c.conname, c.contype, c.convalidated,
                               pg_catalog.pg_get_constraintdef(c.oid, true)
                        FROM pg_catalog.pg_constraint c
                        JOIN pg_catalog.pg_class t ON t.oid = c.conrelid
                        JOIN pg_catalog.pg_namespace n ON n.oid = t.relnamespace
                        WHERE n.nspname = 'public'
                          AND t.relname <> 'flyway_schema_history'
                        ORDER BY t.relname, c.conname
                        """);

                appendQuery(connection, descriptors, "INDEX", """
                        SELECT t.relname, i.relname, x.indisunique, x.indisprimary,
                               x.indisvalid, x.indisready, pg_catalog.pg_get_indexdef(i.oid)
                        FROM pg_catalog.pg_index x
                        JOIN pg_catalog.pg_class t ON t.oid = x.indrelid
                        JOIN pg_catalog.pg_class i ON i.oid = x.indexrelid
                        JOIN pg_catalog.pg_namespace n ON n.oid = t.relnamespace
                        WHERE n.nspname = 'public'
                          AND t.relname <> 'flyway_schema_history'
                        ORDER BY t.relname, i.relname
                        """);

                sequenceCount = appendQuery(connection, descriptors, "SEQUENCE", """
                        SELECT c.relname, s.seqtypid::regtype::text, s.seqstart, s.seqincrement,
                               s.seqmin, s.seqmax, s.seqcache, s.seqcycle
                        FROM pg_catalog.pg_class c
                        JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                        JOIN pg_catalog.pg_sequence s ON s.seqrelid = c.oid
                        WHERE n.nspname = 'public'
                          AND c.relkind = 'S'
                        ORDER BY c.relname
                        """);

                appendQuery(connection, descriptors, "SCHEMA_OWNER", """
                        SELECT n.nspname, r.rolname
                        FROM pg_catalog.pg_namespace n
                        JOIN pg_catalog.pg_roles r ON r.oid = n.nspowner
                        WHERE n.nspname = 'public'
                        """);

                appendQuery(connection, descriptors, "OBJECT_OWNER", """
                        SELECT c.relname, c.relkind, r.rolname
                        FROM pg_catalog.pg_class c
                        JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                        JOIN pg_catalog.pg_roles r ON r.oid = c.relowner
                        WHERE n.nspname = 'public'
                          AND c.relname <> 'flyway_schema_history'
                          AND c.relkind IN ('r', 'p', 'v', 'm', 'S', 'i', 'I')
                          AND NOT (
                              c.relkind IN ('i', 'I')
                              AND EXISTS (
                                  SELECT 1
                                  FROM pg_catalog.pg_index flyway_index
                                  JOIN pg_catalog.pg_class flyway_table
                                    ON flyway_table.oid = flyway_index.indrelid
                                  WHERE flyway_index.indexrelid = c.oid
                                    AND flyway_table.relname = 'flyway_schema_history'
                              )
                          )
                        ORDER BY c.relname, c.relkind
                        """);
                connection.commit();
            } catch (Throwable snapshotFailure) {
                rollbackAddingFailure(connection, snapshotFailure);
                if (snapshotFailure instanceof SQLException sqlException) {
                    throw sqlException;
                }
                if (snapshotFailure instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                if (snapshotFailure instanceof Error error) {
                    throw error;
                }
                throw new SQLException("Unable to capture application schema metadata.", snapshotFailure);
            }
        }
        return new SchemaSnapshot(descriptors, tableCount, sequenceCount);
    }

    private static int appendQuery(Connection connection, List<String> descriptors, String category, String sql)
            throws SQLException {
        int rows = 0;
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            int columns = result.getMetaData().getColumnCount();
            while (result.next()) {
                StringBuilder descriptor = new StringBuilder(category);
                for (int column = 1; column <= columns; column++) {
                    descriptor.append('|').append(canonicalValue(result.getString(column)));
                }
                descriptors.add(descriptor.toString());
                rows++;
            }
        }
        return rows;
    }

    private static String canonicalValue(String value) {
        return value == null ? "<NULL>" : value.replace("\\", "\\\\").replace("|", "\\|");
    }

    private static void assertReadOnlyTransaction(Connection connection) throws SQLException {
        assertThat(connection.isReadOnly()).as("schema snapshot connection read-only flag").isTrue();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SHOW transaction_read_only")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).as("schema snapshot transaction_read_only").isEqualTo("on");
        }
    }

    private static void assertSnapshotsUnchanged(SchemaSnapshot before, SchemaSnapshot after) throws Exception {
        Map<String, Integer> beforeCounts = multiset(before.descriptors());
        Map<String, Integer> afterCounts = multiset(after.descriptors());
        assertThat(after.applicationTableCount()).isEqualTo(before.applicationTableCount());
        assertThat(after.applicationSequenceCount()).isEqualTo(before.applicationSequenceCount());
        assertThat(afterCounts)
                .as("readable application schema descriptor multiset before/after Hibernate validation; before fingerprint=%s, after fingerprint=%s",
                        before.fingerprint(), after.fingerprint())
                .isEqualTo(beforeCounts);
        assertThat(after.fingerprint()).isEqualTo(before.fingerprint());
    }

    private static Map<String, Integer> multiset(List<String> descriptors) {
        Map<String, Integer> counts = new TreeMap<>();
        for (String descriptor : descriptors) {
            counts.merge(descriptor, 1, Integer::sum);
        }
        return counts;
    }

    private static void rollbackAddingFailure(Connection connection, Throwable primary) {
        try {
            connection.rollback();
        } catch (Throwable rollbackFailure) {
            if (rollbackFailure != primary) {
                primary.addSuppressed(rollbackFailure);
            }
        }
    }

    private static void rethrowDatabaseCheckFailure(Throwable failure) throws SQLException {
        if (failure == null) {
            return;
        }
        if (failure instanceof SQLException sqlException) {
            throw sqlException;
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new SQLException("Database identity or read-only verification failed.", failure);
    }

    private static Throwable closeSessionFactory(SessionFactory sessionFactory, Throwable failure) {
        if (sessionFactory == null) {
            return failure;
        }
        try {
            sessionFactory.close();
            return failure;
        } catch (Throwable closeFailure) {
            return addFailure(failure, closeFailure);
        }
    }

    private static Throwable destroyRegistry(StandardServiceRegistry registry, Throwable failure) {
        if (registry == null) {
            return failure;
        }
        try {
            StandardServiceRegistryBuilder.destroy(registry);
            return failure;
        } catch (Throwable destroyFailure) {
            return addFailure(failure, destroyFailure);
        }
    }

    private static Throwable closeDataSource(HikariDataSource dataSource, Throwable failure) {
        try {
            dataSource.close();
            return failure;
        } catch (Throwable closeFailure) {
            return addFailure(failure, closeFailure);
        }
    }

    private static Throwable addFailure(Throwable primary, Throwable additional) {
        if (primary == null) {
            return additional;
        }
        if (primary != additional) {
            primary.addSuppressed(additional);
        }
        return primary;
    }

    private static final class SchemaSnapshot {
        private final List<String> descriptors;
        private final int applicationTableCount;
        private final int applicationSequenceCount;

        private SchemaSnapshot(List<String> descriptors, int applicationTableCount, int applicationSequenceCount) {
            this.descriptors = List.copyOf(descriptors);
            this.applicationTableCount = applicationTableCount;
            this.applicationSequenceCount = applicationSequenceCount;
        }

        private List<String> descriptors() {
            return descriptors;
        }

        private int applicationTableCount() {
            return applicationTableCount;
        }

        private int applicationSequenceCount() {
            return applicationSequenceCount;
        }

        private String fingerprint() throws Exception {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<String> sorted = new ArrayList<>(descriptors);
            Collections.sort(sorted);
            for (String descriptor : sorted) {
                digest.update(descriptor.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        }
    }
}
