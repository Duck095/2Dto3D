package com.exe.morphia3d.dto.response;

import com.exe.morphia3d.enums.MediaAssetState;

import java.time.Instant;

public record AdminProductPrintAssetResponse(
        Long mediaAssetId,
        String fileName,
        long byteCount,
        MediaAssetState status,
        Instant uploadedAt) {
}
