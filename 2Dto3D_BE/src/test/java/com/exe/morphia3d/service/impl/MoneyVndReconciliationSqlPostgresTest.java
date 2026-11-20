package com.exe.morphia3d.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class MoneyVndReconciliationSqlPostgresTest {
    private static final Pattern SQL_FENCE = Pattern.compile("(?s)```sql\\R(.*?)\\R```");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("money_vnd_reconciliation_qa")
            .withUsername("money_vnd_qa")
            .withPassword("money_vnd_qa_only");

    @Test
    void everyDocumentedReadOnlyQueryRunsAgainstDisposablePostgresFixtures() throws IOException {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        createFixtureSchema(jdbc);
        insertFixtureRows(jdbc);

        String document = Files.readString(Path.of("docs/database/money-vnd-reconciliation-readiness.md"));
        Matcher queries = SQL_FENCE.matcher(document);
        int executed = 0;
        while (queries.find()) {
            jdbc.queryForList(queries.group(1));
            executed++;
        }

        assertThat(executed).isEqualTo(11);
    }

    private DataSource dataSource() {
        return new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private void createFixtureSchema(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE price_estimations (
                    estimate_id INTEGER PRIMARY KEY,
                    material_cost DOUBLE PRECISION,
                    labor_cost DOUBLE PRECISION,
                    total_price DOUBLE PRECISION,
                    total_price_vnd BIGINT
                )
                """);
        jdbc.execute("CREATE TABLE materials (material_id INTEGER PRIMARY KEY, price_per_gram DOUBLE PRECISION)");
        jdbc.execute("""
                CREATE TABLE orders (
                    order_id INTEGER PRIMARY KEY,
                    total_amount DOUBLE PRECISION,
                    total_amount_vnd BIGINT,
                    status VARCHAR(20)
                )
                """);
        jdbc.execute("""
                CREATE TABLE order_items (
                    order_item_id INTEGER PRIMARY KEY,
                    order_id INTEGER,
                    estimate_id INTEGER,
                    quantity INTEGER,
                    price_snapshot DOUBLE PRECISION,
                    price_snapshot_vnd BIGINT
                )
                """);
        jdbc.execute("""
                CREATE TABLE payments (
                    payment_id INTEGER PRIMARY KEY,
                    order_id INTEGER,
                    amount DOUBLE PRECISION,
                    amount_vnd BIGINT,
                    status VARCHAR(20),
                    method VARCHAR(20),
                    paid_at TIMESTAMP
                )
                """);
    }

    private void insertFixtureRows(JdbcTemplate jdbc) {
        jdbc.execute("""
                INSERT INTO price_estimations VALUES
                    (1, 2.5, 3.5, 10.5, NULL),
                    (2, NULL, 0, 'NaN'::double precision, NULL)
                """);
        jdbc.execute("INSERT INTO materials VALUES (1, 0.25)");
        jdbc.execute("""
                INSERT INTO orders VALUES
                    (1, 31.0, NULL, 'PENDING'),
                    (2, 'Infinity'::double precision, NULL, 'SUCCEEDED')
                """);
        jdbc.execute("""
                INSERT INTO order_items VALUES
                    (11, 1, 1, 2, 10.5, NULL),
                    (12, 1, 99, 0, 5.0, NULL)
                """);
        jdbc.execute("""
                INSERT INTO payments VALUES
                    (21, 1, 30.0, NULL, 'SUCCESS', 'VNPAY', CURRENT_TIMESTAMP),
                    (22, 99, 4.0, NULL, 'PENDING', 'MOMO', NULL)
                """);
    }
}
