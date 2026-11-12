package com.exe.morphia3d.repository;

import com.exe.morphia3d.entity.AiGenerationJob;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AiGenerationJobRepository extends JpaRepository<AiGenerationJob, UUID> {
    @EntityGraph(attributePaths = {"retryOf", "resultModel"})
    Optional<AiGenerationJob> findByIdAndUser_Id(UUID id, Integer userId);

    Page<AiGenerationJob> findByUser_IdOrderByCreatedAtDescIdDesc(Integer userId, Pageable pageable);

    Optional<AiGenerationJob> findByUser_IdAndIdempotencyKey(Integer userId, String idempotencyKey);
}
