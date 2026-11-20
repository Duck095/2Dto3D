package com.exe.morphia3d.service.ai;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** TEST_ONLY in-memory artifact store. It does not call Cloudinary or external storage. */
public final class TestOnlyAiGenerationArtifactStore implements AiGenerationArtifactStore {
    private final Map<String, byte[]> values = new ConcurrentHashMap<>();
    @Override public boolean isConfigured() { return true; }
    @Override public String storeInput(UUID jobId, String contentType, byte[] bytes) {
        String key = "TEST_ONLY/input/" + jobId;
        values.put(key, bytes.clone());
        return key;
    }
    @Override public InputStream openInput(String key) { return stream(key); }
    @Override public StoredArtifact storeResult(UUID jobId, byte[] bytes) {
        String key = "TEST_ONLY/result/" + jobId;
        values.put(key, bytes.clone());
        return new StoredArtifact(key, "model/gltf-binary", bytes.length);
    }
    @Override public InputStream openResult(String key) { return stream(key); }
    @Override public void delete(String key) { values.remove(key); }

    private InputStream stream(String key) {
        byte[] bytes = values.get(key);
        if (bytes == null) throw new AiGenerationUnavailableException("AI_ARTIFACT_NOT_FOUND");
        return new ByteArrayInputStream(bytes.clone());
    }
}
