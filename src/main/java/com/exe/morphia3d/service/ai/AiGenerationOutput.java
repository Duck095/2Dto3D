package com.exe.morphia3d.service.ai;

import java.util.Objects;

public record AiGenerationOutput(byte[] glbBytes, String title) {
    public AiGenerationOutput {
        glbBytes = Objects.requireNonNull(glbBytes, "glbBytes").clone();
    }

    @Override
    public byte[] glbBytes() {
        return glbBytes.clone();
    }
}
