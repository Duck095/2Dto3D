package com.exe.morphia3d.service.ai;

import java.io.InputStream;
import java.util.UUID;

public interface AiGenerationArtifactStore {
    boolean isConfigured();

    String storeInput(UUID jobId, String contentType, byte[] bytes);

    InputStream openInput(String key);

    StoredArtifact storeResult(UUID jobId, byte[] bytes);

    InputStream openResult(String key);

    void delete(String key);
}
