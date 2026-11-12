package com.exe.morphia3d.service.ai;

import java.util.UUID;

/** TEST_ONLY deterministic provider. Never register outside test configurations. */
public final class TestOnlyAiGenerationProvider implements AiGenerationProvider {
    @Override public boolean isConfigured() { return true; }
    @Override public boolean supportsIdempotency() { return true; }
    @Override public AiGenerationOutput generate(AiGenerationInput input, UUID jobId) {
        return new AiGenerationOutput(testGlb(), "TEST_ONLY generated model");
    }

    private byte[] testGlb() {
        byte[] bytes = new byte[12];
        bytes[0] = 'g'; bytes[1] = 'l'; bytes[2] = 'T'; bytes[3] = 'F';
        bytes[4] = 2; bytes[8] = 12;
        return bytes;
    }
}
