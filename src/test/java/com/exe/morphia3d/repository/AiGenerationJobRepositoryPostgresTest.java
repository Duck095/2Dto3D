package com.exe.morphia3d.repository;

import com.exe.morphia3d.entity.AiGenerationJob;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.AiGenerationStatus;
import com.exe.morphia3d.enums.Role;
import com.exe.morphia3d.enums.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.util.UUID;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class AiGenerationJobRepositoryPostgresTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ai_generation_repository_test")
            .withUsername("ai_generation_repository_test")
            .withPassword("ai_generation_repository_test_only");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        properties.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @Autowired
    private AiGenerationJobRepository jobs;

    @Autowired
    private UserRepository users;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void jobReadsAreScopedToOwnerAndHistoryIsStableNewestFirst() throws InterruptedException {
        User firstOwner = users.saveAndFlush(user("first"));
        User secondOwner = users.saveAndFlush(user("second"));
        AiGenerationJob older = jobs.saveAndFlush(job(firstOwner, "older-key"));
        Thread.sleep(5);
        AiGenerationJob newer = jobs.saveAndFlush(job(firstOwner, "newer-key"));
        jobs.saveAndFlush(job(secondOwner, "other-owner-key"));

        assertThat(jobs.findByIdAndUser_Id(older.getId(), firstOwner.getId()))
                .get()
                .extracting(AiGenerationJob::getStatus)
                .isEqualTo(AiGenerationStatus.PENDING);
        assertThat(jobs.findByIdAndUser_Id(older.getId(), secondOwner.getId())).isEmpty();
        assertThat(jobs.findByUser_IdOrderByCreatedAtDescIdDesc(firstOwner.getId(), PageRequest.of(0, 10))
                .getContent())
                .extracting(AiGenerationJob::getId)
                .containsExactly(newer.getId(), older.getId());
        assertThat(jobs.findByUser_IdAndIdempotencyKey(firstOwner.getId(), "older-key"))
                .contains(older);
    }

    @Test
    void atomicClaimDoesNotClaimAnActiveJobTwice() {
        User owner = users.saveAndFlush(user("claim"));
        AiGenerationJob pending = jobs.saveAndFlush(job(owner, "claim-key"));
        Instant now = Instant.now();
        assertThat(jobs.claimNextPending("worker-one", now.plusSeconds(30), now, 2)).isEqualTo(1);

        entityManager.flush();
        entityManager.clear();
        AiGenerationJob claimed = jobs.findById(pending.getId()).orElseThrow();
        assertThat(claimed.getStatus()).isEqualTo(AiGenerationStatus.PROCESSING);
        assertThat(claimed.getAttemptCount()).isEqualTo(1);
        assertThat(jobs.claimNextPending("worker-two", now.plusSeconds(30), now, 2)).isZero();
    }

    private User user(String suffix) {
        return User.builder()
                .name("AI generation " + suffix)
                .email("ai-generation-" + suffix + "@example.invalid")
                .password("test-only")
                .role(Role.USER)
                .status(UserStatus.ACTIVE)
                .build();
    }

    private AiGenerationJob job(User owner, String idempotencyKey) {
        return AiGenerationJob.builder()
                .id(UUID.randomUUID())
                .user(owner)
                .idempotencyKey(idempotencyKey)
                .requestHash("a".repeat(64))
                .prompt("test-only prompt")
                .style("Realistic")
                .quality("Standard")
                .inputMode("text")
                .status(AiGenerationStatus.PENDING)
                .attemptCount(0)
                .build();
    }
}
