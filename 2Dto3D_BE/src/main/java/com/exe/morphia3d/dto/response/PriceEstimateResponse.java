package com.exe.morphia3d.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PriceEstimateResponse(
        Integer estimateId,
        Integer modelId,
        Integer materialId,
        Integer printerId,
        BigDecimal materialCostVnd,
        BigDecimal machineCostVnd,
        BigDecimal electricityCostVnd,
        BigDecimal productionCostVnd,
        BigDecimal failureAdjustedCostVnd,
        BigDecimal totalCostVnd,
        Long totalPriceVnd,
        String policyVersion,
        LocalDateTime expiresAt,
        String status) {
}
