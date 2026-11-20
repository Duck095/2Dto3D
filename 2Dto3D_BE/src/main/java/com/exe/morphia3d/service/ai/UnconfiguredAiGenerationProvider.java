package com.exe.morphia3d.service.ai;

import java.util.UUID;

public final class UnconfiguredAiGenerationProvider implements AiGenerationProvider {
    @Override
    public boolean isConfigured() {
        return false;
    }

    @Override
    public boolean supportsIdempotency() {
        return false;
    }

    @Override
    public AiGenerationOutput generate(AiGenerationInput input, UUID jobId) {
        throw new AiGenerationUnavailableException("AI_PROVIDER_NOT_CONFIGURED");
    }
}
