package com.exe.morphia3d.service.ai;

import java.util.UUID;

public interface AiGenerationProvider {
    boolean isConfigured();

    boolean supportsIdempotency();

    AiGenerationOutput generate(AiGenerationInput input, UUID jobId);
}
