package com.exe.morphia3d.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import com.exe.morphia3d.enums.MediaKind;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Supplemental row-lock smoke test; migration-backed PostgreSQL certification lives in PostgresV1V2SchemaIntegrationTest. */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "spring.flyway.enabled=false",
        "media.lifecycle.reconciliation-interval-millis=3600000"
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = false)
class MediaAssetLifecyclePostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse(
            "postgres@sha256:721873c34ceb9f8d8fc265984940dc982404c105f19ad51be9fdc5970a6080ea")
            .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("media_lifecycle")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired
    private MediaAssetLifecycleService lifecycle;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void postgresRowLockAllowsOnlyOneConcurrentCleanupLease() throws Exception {
        UUID operationId = UUID.randomUUID();
        MediaAssetOperationLease operation = lifecycle.createUploadIntents(operationId,
                List.of(new MediaAssetIntentSpec(MediaKind.IMAGE, "png", 3L)));
        long id = operation.mediaAssetIds().get(0);
        lifecycle.requestCleanup(operation, List.of(id));
        Instant expiredAt = Instant.now().minusSeconds(600);
        jdbc.update("UPDATE media_asset SET lease_until = ?, updated_at = ? WHERE id = ?",
                Timestamp.from(expiredAt), Timestamp.from(expiredAt), id);

        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<MediaAssetCleanupLease> claim = () -> lifecycle.claimCleanup(id, UUID.randomUUID().toString());
            var first = pool.submit(claim);
            var second = pool.submit(claim);

            assertThat(Stream.of(first.get(), second.get()).filter(java.util.Objects::nonNull).count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
