package com.exe.morphia3d.repository;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class AiGenerationJobMigrationPostgresTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ai_generation_isolated_test")
            .withUsername("ai_generation_test")
            .withPassword("ai_generation_test_only");

    @Test
    void v9CreatesDurableJobsWithoutChangingLegacyGenerationSessionsOrModels() {
        DataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway throughV8 = flyway(dataSource, MigrationVersion.fromVersion("8"));
        assertThat(throughV8.migrate().migrationsExecuted).isEqualTo(8);

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Integer userId = jdbc.queryForObject("""
                INSERT INTO users(name, email, password, role, status)
                VALUES ('AI generation migration fixture', 'ai-generation-migration@example.invalid', 'test-only', 'USER', 'ACTIVE')
                RETURNING user_id
                """, Integer.class);
        Integer sessionId = jdbc.queryForObject("""
                INSERT INTO ai_generation_sessions(user_id, prompt, created_at)
                VALUES (?, 'legacy session must remain unchanged', TIMESTAMP '2026-01-01 00:00:00')
                RETURNING session_id
                """, Integer.class, userId);
        Integer modelId = jdbc.queryForObject("""
                INSERT INTO model_3d(source_type, user_id, name, model_status, session_id)
                VALUES ('AI_MODEL', ?, 'legacy AI model', 'PENDING', ?)
                RETURNING model_id
                """, Integer.class, userId, sessionId);

        Flyway current = flyway(dataSource, null);
        assertThat(current.migrate().migrationsExecuted).isEqualTo(1);
        current.validate();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_generation_jobs", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT prompt FROM ai_generation_sessions WHERE session_id = ? AND user_id = ?
                """, String.class, sessionId, userId)).isEqualTo("legacy session must remain unchanged");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM model_3d WHERE model_id = ? AND session_id = ? AND user_id = ?
                """, Integer.class, modelId, sessionId, userId)).isEqualTo(1);

        assertThat(jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'ai_generation_jobs'
                """, String.class)).containsAll(List.of(
                "generation_job_id", "user_id", "status", "prompt", "style", "quality", "input_mode",
                "input_artifact_key", "request_hash", "idempotency_key", "retry_of",
                "provider_reference", "attempt_count", "error_code", "error_message", "result_model_id",
                "result_artifact_key", "processing_lease_owner", "processing_lease_until", "created_at",
                "updated_at", "finished_at"));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM pg_constraint
                WHERE connamespace = 'public'::regnamespace
                  AND conname IN ('ck_ai_generation_jobs_status', 'ck_ai_generation_jobs_attempt_count',
                                  'uq_ai_generation_jobs_owner_idempotency')
                """, Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM pg_indexes
                WHERE schemaname = 'public'
                  AND indexname IN ('ix_ai_generation_jobs_owner_history', 'ix_ai_generation_jobs_pending_claim')
                """, Integer.class)).isEqualTo(2);
        assertThat(current.migrate().migrationsExecuted).isZero();
    }

    private Flyway flyway(DataSource dataSource, MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .schemas("public")
                .defaultSchema("public")
                .cleanDisabled(true);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }
}
