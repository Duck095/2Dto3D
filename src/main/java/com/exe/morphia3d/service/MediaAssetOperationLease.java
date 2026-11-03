package com.exe.morphia3d.service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Server-issued liveness token shared by every media intent in one upload operation. */
public record MediaAssetOperationLease(UUID operationId, UUID leaseToken, String leaseOwner,
                                       List<Long> mediaAssetIds) {
    public MediaAssetOperationLease {
        Objects.requireNonNull(operationId, "operation ID is required");
        Objects.requireNonNull(leaseToken, "operation lease token is required");
        Objects.requireNonNull(leaseOwner, "operation lease owner is required");
        if (leaseOwner.isBlank() || leaseOwner.length() > 128) {
            throw new IllegalArgumentException("operation lease owner must contain 1 to 128 characters");
        }
        mediaAssetIds = List.copyOf(Objects.requireNonNull(mediaAssetIds, "media asset IDs are required"));
        if (mediaAssetIds.isEmpty() || mediaAssetIds.stream().anyMatch(id -> id == null || id <= 0)
                || mediaAssetIds.stream().distinct().count() != mediaAssetIds.size()) {
            throw new IllegalArgumentException("unique positive media asset IDs are required");
        }
    }

    public boolean owns(long mediaAssetId) {
        return mediaAssetIds.contains(mediaAssetId);
    }
}
