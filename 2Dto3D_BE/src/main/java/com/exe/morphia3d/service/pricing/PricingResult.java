package com.exe.morphia3d.service.pricing;

import java.math.BigDecimal;

public record PricingResult(
        BigDecimal materialCostVnd,
        BigDecimal machineCostVnd,
        BigDecimal electricityCostVnd,
        BigDecimal productionCostVnd,
        BigDecimal failureAdjustedCostVnd,
        BigDecimal totalCostVnd,
        BigDecimal sellingPriceVnd) {
}
