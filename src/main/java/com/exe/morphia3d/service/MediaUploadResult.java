package com.exe.morphia3d.service;

import com.exe.morphia3d.enums.MediaKind;
import com.exe.morphia3d.enums.MediaProvider;

public record MediaUploadResult(
        MediaProvider provider,
        String cloudName,
        String providerAssetId,
        String publicId,
        MediaKind mediaKind,
        String resourceType,
        String deliveryType,
        String format,
        long byteCount,
        long providerVersion,
        String secureUrl) {
}
