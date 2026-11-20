package com.exe.morphia3d.service.pricing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PricingEngineTest {
    private final PricingEngine engine = new PricingEngine();

    @Test
    void calculatesConfiguredCostsFailureAdjustmentMarginAndVndRounding() {
        PricingPolicy policy = new PricingPolicy(
                new BigDecimal("10000"), new BigDecimal("3000"), new BigDecimal("0.1"),
                new BigDecimal("5000"), new BigDecimal("1000"), new BigDecimal("1000"),
                new BigDecimal("0.1"), new BigDecimal("0.3"), new BigDecimal("0.1"),
                new BigDecimal("50000"), new BigDecimal("1000"));

        PricingResult result = engine.calculate(new PricingInput(
                new BigDecimal("20"), new BigDecimal("100"), new BigDecimal("2")), policy);

        assertThat(result.materialCostVnd()).isEqualByComparingTo("2000");
        assertThat(result.machineCostVnd()).isEqualByComparingTo("20000");
        assertThat(result.electricityCostVnd()).isEqualByComparingTo("600.0");
        assertThat(result.productionCostVnd()).isEqualByComparingTo("22600.0");
        assertThat(result.failureAdjustedCostVnd()).isEqualByComparingTo("25111.1111111111");
        assertThat(result.totalCostVnd()).isEqualByComparingTo("32111.1111111111");
        assertThat(result.sellingPriceVnd()).isEqualByComparingTo("54000");
    }

    @Test
    void failsClosedWhenARequiredPricingInputIsMissing() {
        PricingPolicy incomplete = new PricingPolicy(
                null, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                null, BigDecimal.ONE);

        assertThatThrownBy(() -> engine.calculate(new PricingInput(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE), incomplete))
                .isInstanceOf(PricingNotConfiguredException.class)
                .hasMessageContaining("machineRateVndPerHour");
    }

    @Test
    void rejectsFailureRateOrMarginAndFeeDenominatorOutsidePolicyRange() {
        PricingInput input = new PricingInput(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);
        PricingPolicy invalidFailureRate = new PricingPolicy(
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, null, BigDecimal.ONE);
        PricingPolicy invalidSellingRates = new PricingPolicy(
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("0.7"), new BigDecimal("0.3"), null, BigDecimal.ONE);

        assertThatThrownBy(() -> engine.calculate(input, invalidFailureRate))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.calculate(input, invalidSellingRates))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativeRequiredInputsAndNonPositiveRoundingIncrement() {
        PricingPolicy complete = new PricingPolicy(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, null, BigDecimal.ONE);
        PricingPolicy noRounding = new PricingPolicy(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, null, BigDecimal.ZERO);

        assertThatThrownBy(() -> engine.calculate(
                new PricingInput(new BigDecimal("-1"), BigDecimal.ONE, BigDecimal.ONE), complete))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("consumedMaterialGrams");
        assertThatThrownBy(() -> engine.calculate(
                new PricingInput(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE), noRounding))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("roundingIncrementVnd");
    }
}
