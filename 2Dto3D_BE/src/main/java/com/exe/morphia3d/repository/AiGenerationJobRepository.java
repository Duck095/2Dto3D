package com.exe.morphia3d.repository;

import com.exe.morphia3d.entity.AiGenerationJob;
import com.exe.morphia3d.enums.AiGenerationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

public interface AiGenerationJobRepository extends JpaRepository<AiGenerationJob, UUID> {
    @EntityGraph(attributePaths = {"retryOf", "resultModel"})
    Optional<AiGenerationJob> findByIdAndUser_Id(UUID id, Integer userId);

    @EntityGraph(attributePaths = {"retryOf", "resultModel"})
    Page<AiGenerationJob> findByUser_IdOrderByCreatedAtDescIdDesc(Integer userId, Pageable pageable);

    Optional<AiGenerationJob> findByUser_IdAndIdempotencyKey(Integer userId, String idempotencyKey);

    Optional<AiGenerationJob> findFirstByProcessingLeaseOwnerAndStatus(String leaseOwner, AiGenerationStatus status);

    Optional<AiGenerationJob> findByIdAndProcessingLeaseOwner(UUID id, String leaseOwner);

    @Modifying
    @Transactional
    @Query(value = """
            UPDATE ai_generation_jobs
               SET status = 'PROCESSING', attempt_count = attempt_count + 1,
                   processing_lease_owner = :leaseOwner, processing_lease_until = :leaseUntil,
                   next_attempt_at = NULL, updated_at = :now
             WHERE generation_job_id = (
                   SELECT generation_job_id
                     FROM ai_generation_jobs
                    WHERE ((status = 'PENDING' AND (next_attempt_at IS NULL OR next_attempt_at <= :now))
                        OR (status = 'PROCESSING' AND processing_lease_until <= :now))
                      AND attempt_count < :maxAttempts
                    ORDER BY created_at, generation_job_id
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
             )
            """, nativeQuery = true)
    int claimNextPending(@Param("leaseOwner") String leaseOwner, @Param("leaseUntil") Instant leaseUntil,
                         @Param("now") Instant now, @Param("maxAttempts") int maxAttempts);

    @Modifying
    @Transactional
    @Query(value = """
            UPDATE ai_generation_jobs
               SET status = 'FAILED', error_code = 'AI_PROVIDER_OUTCOME_UNKNOWN',
                   error_message = 'The provider outcome could not be confirmed safely.',
                   finished_at = :now, processing_lease_owner = NULL, processing_lease_until = NULL,
                   updated_at = :now
             WHERE status = 'PROCESSING' AND processing_lease_until <= :now
               AND attempt_count >= :maxAttempts
            """, nativeQuery = true)
    int failExpiredExhaustedLeases(@Param("now") Instant now, @Param("maxAttempts") int maxAttempts);
}
