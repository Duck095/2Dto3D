package com.exe.morphia3d.service.pricing;

import java.math.BigDecimal;

public record PricingPolicy(
        BigDecimal machineRateVndPerHour,
        BigDecimal electricityRateVndPerKwh,
        BigDecimal averagePowerKw,
        BigDecimal laborCostVnd,
        BigDecimal packagingCostVnd,
        BigDecimal otherCostVnd,
        BigDecimal failureRate,
        BigDecimal targetMarginRate,
        BigDecimal variableSellingFeeRate,
        BigDecimal minimumSellingPriceVnd,
        BigDecimal roundingIncrementVnd) {
}
