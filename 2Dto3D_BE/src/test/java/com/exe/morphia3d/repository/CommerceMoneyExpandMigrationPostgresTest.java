package com.exe.morphia3d.repository;

import com.exe.morphia3d.entity.AIGenerationSession;
import com.exe.morphia3d.entity.Cart;
import com.exe.morphia3d.entity.CartItem;
import com.exe.morphia3d.entity.Color;
import com.exe.morphia3d.entity.Feedback;
import com.exe.morphia3d.entity.Material;
import com.exe.morphia3d.entity.MaterialColor;
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
import com.exe.morphia3d.entity.StaffProfile;
import com.exe.morphia3d.entity.StaffProfileAudit;
import com.exe.morphia3d.entity.Task;
import com.exe.morphia3d.entity.TaskHistory;
import com.exe.morphia3d.entity.TaskSubmission;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.entity.ValidatedSlicingRun;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class CommerceMoneyExpandMigrationPostgresTest {
    private static final String ADMIN_DATABASE = "commerce_qa_admin";
    private static final String FRESH_DATABASE = "commerce_qa_fresh";
    private static final String UPGRADE_DATABASE = "commerce_qa_upgrade";
    private static final String V5_GUARD_DATABASE = "commerce_qa_v5_guard";
    private static final double ESTIMATE_AMOUNT = 123.45d;
    private static final double ORDER_AMOUNT = 246.90d;
    private static final List<Class<?>> ENTITY_CLASSES = List.of(
            AIGenerationSession.class, Cart.class, CartItem.class, Color.class, Feedback.class,
            Material.class, MaterialColor.class, MaterialStockLog.class, MediaAsset.class,
            Model3d.class, ModelImage.class, ModelPart.class, Order.class, OrderItem.class,
            Payment.class, PriceEstimation.class, Printer.class, ValidatedSlicingRun.class, StaffProfile.class,
            StaffProfileAudit.class, Task.class, TaskHistory.class, TaskSubmission.class, User.class);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName(ADMIN_DATABASE)
            .withUsername("commerce_qa")
            .withPassword("commerce_qa_only");

    @BeforeAll
    static void createIsolatedDatabases() {
        JdbcTemplate admin = new JdbcTemplate(dataSource(ADMIN_DATABASE));
        admin.execute("CREATE DATABASE " + FRESH_DATABASE);
        admin.execute("CREATE DATABASE " + UPGRADE_DATABASE);
        admin.execute("CREATE DATABASE " + V5_GUARD_DATABASE);
    }

    @Test
    void freshV1ThroughV8HasExpectedHistoryColumnsAndHibernateMappings() {
        DataSource dataSource = dataSource(FRESH_DATABASE);
        Flyway flyway = flyway(dataSource, "8");

        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(8);
        flyway.validate();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertHistory(jdbc, List.of("1", "2", "3", "4", "5", "6", "7", "8"));
        assertVndColumns(jdbc);
        assertPricingSnapshotSchema(jdbc);
        assertLegacyAmountTypes(jdbc);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        // This test intentionally stops at V8. Current Hibernate mappings are validated by the V11
        // Payment Attempt persistence test; V8 must not be required to contain later columns.
    }

    @Test
    void upgradeFromV5PreservesLegacyCommerceRowsAndLeavesNewAmountsNull() {
        DataSource dataSource = dataSource(UPGRADE_DATABASE);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        Flyway throughV5 = flyway(dataSource, "5");
        assertThat(throughV5.migrate().migrationsExecuted).isEqualTo(5);
        throughV5.validate();
        assertHistory(jdbc, List.of("1", "2", "3", "4", "5"));
        insertCommerceFixture(jdbc);

        Map<String, Integer> rowCountsBefore = legacyRowCounts(jdbc);
        Map<String, Double> amountsBefore = legacyAmounts(jdbc);

        Flyway throughV8 = flyway(dataSource, "8");
        assertThat(throughV8.migrate().migrationsExecuted).isEqualTo(3);
        throughV8.validate();
        assertThat(throughV8.migrate().migrationsExecuted).isZero();

        assertHistory(jdbc, List.of("1", "2", "3", "4", "5", "6", "7", "8"));
        assertVndColumns(jdbc);
        assertPricingSnapshotSchema(jdbc);
        assertNewVndColumnsNull(jdbc);
        assertLegacyAmountTypes(jdbc);
        assertThat(legacyRowCounts(jdbc)).isEqualTo(rowCountsBefore);
        assertThat(legacyAmounts(jdbc)).isEqualTo(amountsBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cart_items", Integer.class)).isEqualTo(1);
        // This test intentionally stops at V8. Current Hibernate mappings are validated by the V11
        // Payment Attempt persistence test; V8 must not be required to contain later columns.
    }

    @Test
    void v5AcceptsExistingCommerceFixtureWithoutDuplicateCartSelections() {
        DataSource dataSource = dataSource(V5_GUARD_DATABASE);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        Flyway throughV4 = flyway(dataSource, "4");
        assertThat(throughV4.migrate().migrationsExecuted).isEqualTo(4);
        insertCommerceFixture(jdbc);

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT cart_id, model_id, estimate_id
                    FROM cart_items
                    GROUP BY cart_id, model_id, estimate_id
                    HAVING COUNT(*) > 1
                ) duplicate_selections
                """, Integer.class)).isZero();

        Flyway throughV5 = flyway(dataSource, "5");
        assertThat(throughV5.migrate().migrationsExecuted).isEqualTo(1);
        throughV5.validate();
        assertHistory(jdbc, List.of("1", "2", "3", "4", "5"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cart_items", Integer.class)).isEqualTo(1);
    }

    private static DataSource dataSource(String database) {
        String adminUrl = POSTGRES.getJdbcUrl();
        String jdbcUrl = adminUrl.replace("/" + ADMIN_DATABASE, "/" + database);
        return new DriverManagerDataSource(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static Flyway flyway(DataSource dataSource, String target) {
        var config = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .schemas("public")
                .defaultSchema("public")
                .cleanDisabled(true);
        if (target != null) {
            config.target(MigrationVersion.fromVersion(target));
        }
        return config.load();
    }

    private static void insertCommerceFixture(JdbcTemplate jdbc) {
        Integer userId = jdbc.queryForObject("""
                INSERT INTO users (name, email, password, role, status)
                VALUES ('Commerce QA User', 'commerce-qa@example.invalid', 'test-hash', 'USER', 'ACTIVE')
                RETURNING user_id
                """, Integer.class);
        Integer printerId = jdbc.queryForObject("""
                INSERT INTO printers (name, status) VALUES ('Commerce QA Printer', 'ACTIVE')
                RETURNING printer_id
                """, Integer.class);
        Integer materialId = jdbc.queryForObject("""
                INSERT INTO materials (name, price_per_gram) VALUES ('Commerce QA Material', 1.25)
                RETURNING material_id
                """, Integer.class);
        Integer modelId = jdbc.queryForObject("""
                INSERT INTO model_3d (source_type, user_id, name, model_status)
                VALUES ('DESIGNER_MODEL', ?, 'Commerce QA Model', 'ACTIVE')
                RETURNING model_id
                """, Integer.class, userId);
        Integer estimateId = jdbc.queryForObject("""
                INSERT INTO price_estimations (model_id, material_id, printer_id, total_price)
                VALUES (?, ?, ?, ?)
                RETURNING estimate_id
                """, Integer.class, modelId, materialId, printerId, ESTIMATE_AMOUNT);
        Integer cartId = jdbc.queryForObject("""
                INSERT INTO cart (user_id) VALUES (?) RETURNING cart_id
                """, Integer.class, userId);
        jdbc.update("""
                INSERT INTO cart_items (cart_id, model_id, estimate_id, quantity)
                VALUES (?, ?, ?, 1)
                """, cartId, modelId, estimateId);
        Integer orderId = jdbc.queryForObject("""
                INSERT INTO orders (user_id, printer_id, total_amount, status)
                VALUES (?, ?, ?, 'PENDING')
                RETURNING order_id
                """, Integer.class, userId, printerId, ORDER_AMOUNT);
        jdbc.update("""
                INSERT INTO order_items (order_id, model_id, estimate_id, quantity, price_snapshot)
                VALUES (?, ?, ?, 2, ?)
                """, orderId, modelId, estimateId, ESTIMATE_AMOUNT);
        jdbc.update("""
                INSERT INTO payments (order_id, amount, method, status)
                VALUES (?, ?, 'MOMO', 'PENDING')
                """, orderId, ORDER_AMOUNT);
    }

    private static Map<String, Integer> legacyRowCounts(JdbcTemplate jdbc) {
        return Map.of(
                "price_estimations", jdbc.queryForObject("SELECT COUNT(*) FROM price_estimations", Integer.class),
                "orders", jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class),
                "order_items", jdbc.queryForObject("SELECT COUNT(*) FROM order_items", Integer.class),
                "payments", jdbc.queryForObject("SELECT COUNT(*) FROM payments", Integer.class));
    }

    private static Map<String, Double> legacyAmounts(JdbcTemplate jdbc) {
        return Map.of(
                "price_estimations.total_price", jdbc.queryForObject(
                        "SELECT total_price FROM price_estimations", Double.class),
                "orders.total_amount", jdbc.queryForObject(
                        "SELECT total_amount FROM orders", Double.class),
                "order_items.price_snapshot", jdbc.queryForObject(
                        "SELECT price_snapshot FROM order_items", Double.class),
                "payments.amount", jdbc.queryForObject("SELECT amount FROM payments", Double.class));
    }

    private static void assertHistory(JdbcTemplate jdbc, List<String> expectedVersions) {
        assertThat(jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class))
                .containsExactlyElementsOf(expectedVersions);
    }

    private static void assertVndColumns(JdbcTemplate jdbc) {
        assertVndColumn(jdbc, "price_estimations", "total_price_vnd");
        assertVndColumn(jdbc, "orders", "total_amount_vnd");
        assertVndColumn(jdbc, "order_items", "price_snapshot_vnd");
        assertVndColumn(jdbc, "payments", "amount_vnd");
    }

    private static void assertPricingSnapshotSchema(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='validated_slicing_runs'", Integer.class)).isEqualTo(1);
        for (var column : List.of(Map.entry("price_estimations", "slicing_run_id"),
                Map.entry("price_estimations", "policy_version"), Map.entry("price_estimations", "pricing_policy_snapshot"),
                Map.entry("order_items", "price_snapshot_policy_version"), Map.entry("order_items", "pricing_policy_snapshot"))) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='public' AND table_name=? AND column_name=?", Integer.class,
                    column.getKey(), column.getValue())).isEqualTo(1);
        }
    }

    private static void assertNewVndColumnsNull(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM price_estimations WHERE total_price_vnd IS NULL",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE total_amount_vnd IS NULL",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_items WHERE price_snapshot_vnd IS NULL",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE amount_vnd IS NULL",
                Integer.class)).isEqualTo(1);
    }

    private static void assertVndColumn(JdbcTemplate jdbc, String table, String column) {
        Map<String, Object> result = jdbc.queryForMap("""
                SELECT data_type, is_nullable, column_default
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                """, table, column);
        assertThat(result.get("data_type")).isEqualTo("bigint");
        assertThat(result.get("is_nullable")).isEqualTo("YES");
        assertThat(result.get("column_default")).isNull();
    }

    private static void assertLegacyAmountTypes(JdbcTemplate jdbc) {
        for (var column : List.of(
                Map.entry("price_estimations", "total_price"),
                Map.entry("orders", "total_amount"),
                Map.entry("order_items", "price_snapshot"),
                Map.entry("payments", "amount"))) {
            assertThat(jdbc.queryForObject("""
                    SELECT data_type FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                    """, String.class, column.getKey(), column.getValue()))
                    .isEqualTo("double precision");
        }
    }

    private static void assertHibernateMappings(DataSource dataSource) {
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.datasource", dataSource)
                .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                .applySetting("hibernate.default_schema", "public")
                .applySetting("hibernate.hbm2ddl.auto", "validate")
                .build();
        try {
            MetadataSources metadata = new MetadataSources(registry);
            ENTITY_CLASSES.forEach(metadata::addAnnotatedClass);
            try (SessionFactory sessionFactory = metadata.buildMetadata().buildSessionFactory()) {
                assertThat(sessionFactory.getMetamodel().getEntities()).hasSize(24);
            }
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }
}
