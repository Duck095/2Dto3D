package com.exe.morphia3d.dto.response;

public record CartItemResponse(Integer id, Integer modelId, Integer estimateId, Integer quantity,
                               Integer materialId, Integer printerId, Long unitPriceVnd,
                               String policyVersion, java.time.LocalDateTime estimateExpiresAt) {
    public CartItemResponse(Integer id, Integer modelId, Integer estimateId, Integer quantity) {
        this(id, modelId, estimateId, quantity, null, null, null, null, null);
    }
}
