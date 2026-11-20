package com.exe.morphia3d.service.ai;

import java.util.Objects;

public record ValidatedGlb(byte[] bytes) {
    public ValidatedGlb {
        bytes = Objects.requireNonNull(bytes, "bytes").clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    public long byteCount() {
        return bytes.length;
    }
}
