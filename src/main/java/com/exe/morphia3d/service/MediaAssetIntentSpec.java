package com.exe.morphia3d.service;

import com.exe.morphia3d.enums.MediaKind;

import java.util.Objects;

/** Validated media attributes needed to persist an upload intent. */
public record MediaAssetIntentSpec(MediaKind mediaKind, String format, long byteCount) {
    public MediaAssetIntentSpec {
        Objects.requireNonNull(mediaKind, "media kind is required");
        Objects.requireNonNull(format, "format is required");
        if (byteCount <= 0) {
            throw new IllegalArgumentException("byte count must be positive");
        }
    }
}
