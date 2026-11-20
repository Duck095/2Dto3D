package com.exe.morphia3d.dto.response;

import com.exe.morphia3d.enums.AiGenerationStatus;

import java.time.Instant;
import java.util.UUID;

public record AiGenerationJobResponse(
        UUID id,
        AiGenerationStatus status,
        Instant createdAt,
        Instant updatedAt,
        Instant finishedAt,
        String errorCode,
        String errorMessage,
        Integer modelId,
        String modelUrl,
        UUID retryOf) {
}
