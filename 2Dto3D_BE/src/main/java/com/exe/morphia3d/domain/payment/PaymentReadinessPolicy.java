package com.exe.morphia3d.domain.payment;

import java.time.Instant;

/** Pure fail-closed check for payment eligibility; it performs no persistence or provider calls. */
public final class PaymentReadinessPolicy {
    public Blocker evaluate(Snapshot snapshot) {
        if (snapshot == null) {
            return Blocker.TOTAL_NOT_FROZEN;
        }
        if (!snapshot.commercialPricingApproved()) {
            return Blocker.COMMERCIAL_PRICING_UNAPPROVED;
        }
        if (!snapshot.shippingConfirmed()) {
            return Blocker.SHIPPING_UNCONFIRMED;
        }
        if (snapshot.frozenAt() == null) {
            return Blocker.TOTAL_NOT_FROZEN;
        }
        if (snapshot.productSubtotalVnd() <= 0 || snapshot.shippingAmountVnd() < 0
                || snapshot.payableTotalVnd() <= 0) {
            return Blocker.INVALID_AMOUNT;
        }
        final long expected;
        try {
            expected = Math.addExact(snapshot.productSubtotalVnd(), snapshot.shippingAmountVnd());
        } catch (ArithmeticException overflow) {
            return Blocker.INVALID_AMOUNT;
        }
        return expected == snapshot.payableTotalVnd() ? Blocker.NONE : Blocker.FROZEN_TOTAL_MISMATCH;
    }

    public record Snapshot(
            long productSubtotalVnd,
            long shippingAmountVnd,
            long payableTotalVnd,
            Instant frozenAt,
            boolean shippingConfirmed,
            boolean commercialPricingApproved) {
    }

    public enum Blocker {
        NONE,
        COMMERCIAL_PRICING_UNAPPROVED,
        SHIPPING_UNCONFIRMED,
        TOTAL_NOT_FROZEN,
        INVALID_AMOUNT,
        FROZEN_TOTAL_MISMATCH
    }
}
