package com.exe.morphia3d.service.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public final class PricingEngine {
    private static final int CALCULATION_SCALE = 10;
    private static final RoundingMode CALCULATION_ROUNDING = RoundingMode.HALF_UP;

    public PricingResult calculate(PricingInput input, PricingPolicy policy) {
        if (input == null) throw new IllegalArgumentException("Pricing input is required");
        if (policy == null) throw new PricingNotConfiguredException("pricingPolicy");
        requireInput(input.consumedMaterialGrams(), "consumedMaterialGrams");
        requireInput(input.materialPricePerGramVnd(), "materialPricePerGramVnd");
        requireInput(input.printTimeHours(), "printTimeHours");

        List<String> missing = List.of(
                policy.machineRateVndPerHour() == null ? "machineRateVndPerHour" : "",
                policy.electricityRateVndPerKwh() == null ? "electricityRateVndPerKwh" : "",
                policy.averagePowerKw() == null ? "averagePowerKw" : "",
                policy.laborCostVnd() == null ? "laborCostVnd" : "",
                policy.packagingCostVnd() == null ? "packagingCostVnd" : "",
                policy.otherCostVnd() == null ? "otherCostVnd" : "",
                policy.failureRate() == null ? "failureRate" : "",
                policy.targetMarginRate() == null ? "targetMarginRate" : "",
                policy.variableSellingFeeRate() == null ? "variableSellingFeeRate" : "",
                policy.roundingIncrementVnd() == null ? "roundingIncrementVnd" : "");
        String missingValue = missing.stream().filter(value -> !value.isEmpty()).findFirst().orElse(null);
        if (missingValue != null) throw new PricingNotConfiguredException(missingValue);

        requireNonNegative(policy.machineRateVndPerHour(), "machineRateVndPerHour");
        requireNonNegative(policy.electricityRateVndPerKwh(), "electricityRateVndPerKwh");
        requireNonNegative(policy.averagePowerKw(), "averagePowerKw");
        requireNonNegative(policy.laborCostVnd(), "laborCostVnd");
        requireNonNegative(policy.packagingCostVnd(), "packagingCostVnd");
        requireNonNegative(policy.otherCostVnd(), "otherCostVnd");
        requireNonNegative(input.consumedMaterialGrams(), "consumedMaterialGrams");
        requireNonNegative(input.materialPricePerGramVnd(), "materialPricePerGramVnd");
        requireNonNegative(input.printTimeHours(), "printTimeHours");
        requireRate(policy.failureRate(), "failureRate");
        requireRate(policy.targetMarginRate(), "targetMarginRate");
        requireRate(policy.variableSellingFeeRate(), "variableSellingFeeRate");
        if (policy.failureRate().compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException("failureRate must be less than 1");
        }
        if (policy.targetMarginRate().add(policy.variableSellingFeeRate()).compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException("targetMarginRate plus variableSellingFeeRate must be less than 1");
        }
        if (policy.minimumSellingPriceVnd() != null) {
            requireNonNegative(policy.minimumSellingPriceVnd(), "minimumSellingPriceVnd");
        }
        if (policy.roundingIncrementVnd().signum() <= 0) {
            throw new IllegalArgumentException("roundingIncrementVnd must be greater than 0");
        }

        BigDecimal material = input.consumedMaterialGrams().multiply(input.materialPricePerGramVnd());
        BigDecimal machine = input.printTimeHours().multiply(policy.machineRateVndPerHour());
        BigDecimal electricity = input.printTimeHours()
                .multiply(policy.averagePowerKw())
                .multiply(policy.electricityRateVndPerKwh());
        BigDecimal production = material.add(machine).add(electricity);
        BigDecimal failureAdjusted = production.divide(BigDecimal.ONE.subtract(policy.failureRate()),
                CALCULATION_SCALE, CALCULATION_ROUNDING);
        BigDecimal total = failureAdjusted.add(policy.laborCostVnd())
                .add(policy.packagingCostVnd()).add(policy.otherCostVnd());
        BigDecimal denominator = BigDecimal.ONE.subtract(policy.targetMarginRate())
                .subtract(policy.variableSellingFeeRate());
        BigDecimal selling = total.divide(denominator, CALCULATION_SCALE, CALCULATION_ROUNDING);
        if (policy.minimumSellingPriceVnd() != null && selling.compareTo(policy.minimumSellingPriceVnd()) < 0) {
            selling = policy.minimumSellingPriceVnd();
        }
        selling = selling.divide(policy.roundingIncrementVnd(), 0, RoundingMode.CEILING)
                .multiply(policy.roundingIncrementVnd());
        if (selling.signum() <= 0) {
            throw new PricingNotConfiguredException("positiveSellingPrice");
        }

        return new PricingResult(material, machine, electricity, production, failureAdjusted, total, selling);
    }

    private static void requireInput(BigDecimal value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " is required");
    }

    private static void requireNonNegative(BigDecimal value, String name) {
        if (value.signum() < 0) throw new IllegalArgumentException(name + " must not be negative");
    }

    private static void requireRate(BigDecimal value, String name) {
        if (value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(name + " must be between 0 and 1");
        }
    }
}
