package com.exe.morphia3d.service;

import com.exe.morphia3d.enums.MediaKind;
import com.exe.morphia3d.enums.MediaProvider;

import java.util.Objects;

/** Provider identity used for deletion; providerAssetId is supplemental when available. */
public record MediaAssetIdentity(
        MediaProvider provider,
        String cloudName,
        String providerAssetId,
        String publicId,
        MediaKind mediaKind,
        String resourceType,
        String deliveryType) {

    public MediaAssetIdentity {
        Objects.requireNonNull(provider, "provider is required");
        Objects.requireNonNull(mediaKind, "media kind is required");
        requireText(cloudName, "cloud name");
        providerAssetId = optionalText(providerAssetId);
        requireText(publicId, "public ID");
        requireText(resourceType, "resource type");
        requireText(deliveryType, "delivery type");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    private static String optionalText(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
