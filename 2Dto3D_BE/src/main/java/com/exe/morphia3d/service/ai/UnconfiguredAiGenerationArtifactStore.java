package com.exe.morphia3d.service.ai;

import java.io.InputStream;
import java.util.UUID;

public final class UnconfiguredAiGenerationArtifactStore implements AiGenerationArtifactStore {
    @Override
    public boolean isConfigured() {
        return false;
    }

    @Override
    public String storeInput(UUID jobId, String contentType, byte[] bytes) {
        throw unavailable();
    }

    @Override
    public InputStream openInput(String key) {
        throw unavailable();
    }

    @Override
    public StoredArtifact storeResult(UUID jobId, byte[] bytes) {
        throw unavailable();
    }

    @Override
    public InputStream openResult(String key) {
        throw unavailable();
    }

    @Override
    public void delete(String key) {
        throw unavailable();
    }

    private AiGenerationUnavailableException unavailable() {
        return new AiGenerationUnavailableException("AI_ARTIFACT_STORAGE_NOT_CONFIGURED");
    }
}
