package com.exe.morphia3d.domain.payment;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentReadinessPolicyTest {
    private final PaymentReadinessPolicy policy = new PaymentReadinessPolicy();

    @Test
    void onlyAllowsAttemptForBackendFrozenTotalWithApprovedPricingAndConfirmedShipping() {
        assertThat(policy.evaluate(new PaymentReadinessPolicy.Snapshot(
                125_000L, 20_000L, 145_000L, Instant.parse("2026-11-12T00:00:00Z"), true, true)))
                .isEqualTo(PaymentReadinessPolicy.Blocker.NONE);
    }

    @Test
    void failsClosedForUnconfirmedShippingOrCommercialPricing() {
        assertThat(policy.evaluate(snapshot(145_000L, false, true)))
                .isEqualTo(PaymentReadinessPolicy.Blocker.SHIPPING_UNCONFIRMED);
        assertThat(policy.evaluate(snapshot(145_000L, true, false)))
                .isEqualTo(PaymentReadinessPolicy.Blocker.COMMERCIAL_PRICING_UNAPPROVED);
    }

    @Test
    void requiresFrozenTotalToEqualExactLongVndSum() {
        assertThat(policy.evaluate(snapshot(145_001L, true, true)))
                .isEqualTo(PaymentReadinessPolicy.Blocker.FROZEN_TOTAL_MISMATCH);
        assertThat(policy.evaluate(snapshot(0L, true, true)))
                .isEqualTo(PaymentReadinessPolicy.Blocker.INVALID_AMOUNT);
    }

    @Test
    void overflowAndMissingFreezeFailClosed() {
        assertThat(policy.evaluate(new PaymentReadinessPolicy.Snapshot(
                Long.MAX_VALUE, 1L, Long.MAX_VALUE, Instant.now(), true, true)))
                .isEqualTo(PaymentReadinessPolicy.Blocker.INVALID_AMOUNT);
        assertThat(policy.evaluate(new PaymentReadinessPolicy.Snapshot(
                100L, 0L, 100L, null, true, true)))
                .isEqualTo(PaymentReadinessPolicy.Blocker.TOTAL_NOT_FROZEN);
    }

    private PaymentReadinessPolicy.Snapshot snapshot(long frozen, boolean shippingConfirmed, boolean pricingApproved) {
        return new PaymentReadinessPolicy.Snapshot(125_000L, 20_000L, frozen, Instant.now(), shippingConfirmed, pricingApproved);
    }
}
