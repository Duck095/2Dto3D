package com.exe.morphia3d.service;

import java.util.Objects;
import java.util.UUID;

/** Short-lived ownership token for one cleanup attempt. */
public record MediaAssetCleanupLease(long mediaAssetId, UUID operationId, UUID leaseToken) {
    public MediaAssetCleanupLease {
        if (mediaAssetId <= 0) {
            throw new IllegalArgumentException("media asset ID must be positive");
        }
        Objects.requireNonNull(operationId, "operation ID is required");
        Objects.requireNonNull(leaseToken, "lease token is required");
    }
}
