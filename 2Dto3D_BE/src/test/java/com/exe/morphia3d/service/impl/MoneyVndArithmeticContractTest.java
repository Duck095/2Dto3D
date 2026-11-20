package com.exe.morphia3d.service.impl;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reference arithmetic contract for a future integer-VND implementation.
 * These helpers deliberately do not call or represent production payment code.
 */
class MoneyVndArithmeticContractTest {

    @Test
    void roundsTheCompletedQuoteHalfUpBelowAtAndAboveHalf() {
        assertThat(finalQuote(new BigDecimal("123.49"))).isEqualTo(123L);
        assertThat(finalQuote(new BigDecimal("123.50"))).isEqualTo(124L);
        assertThat(finalQuote(new BigDecimal("123.51"))).isEqualTo(124L);
        assertThat(roundHalfUp(new BigDecimal("-123.50"))).isEqualTo(-124L);
    }

    @Test
    void roundsOnceAfterCombiningQuoteComponentsThenUsesIntegerSnapshots() {
        BigDecimal completedQuote = new BigDecimal("0.49")
                .add(new BigDecimal("0.49"))
                .add(new BigDecimal("0.49"));

        long unitSnapshot = finalQuote(completedQuote);

        assertThat(unitSnapshot).isEqualTo(1L);
        assertThat(lineTotal(unitSnapshot, 3)).isEqualTo(3L);
        assertThat(roundHalfUp(new BigDecimal("0.49"))
                + roundHalfUp(new BigDecimal("0.49"))
                + roundHalfUp(new BigDecimal("0.49"))).isZero();
    }

    @Test
    void acceptsOnlyPositivePayableVndAmounts() {
        assertThat(finalQuote(new BigDecimal("0.50"))).isEqualTo(1L);
        assertThatThrownBy(() -> finalQuote(new BigDecimal("0")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> finalQuote(new BigDecimal("-1.00")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> finalQuote(new BigDecimal("0.49")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> finalQuote(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void convertsOnlyRoundedValuesWithinTheBigintRange() {
        assertThat(finalQuote(BigDecimal.valueOf(Long.MAX_VALUE)))
                .isEqualTo(Long.MAX_VALUE);
        assertThatThrownBy(() -> finalQuote(new BigDecimal("9223372036854775807.50")))
                .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> finalQuote(new BigDecimal("9223372036854775808")))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void detectsLineMultiplicationAndOrderAdditionOverflow() {
        assertThat(lineTotal(125L, 4)).isEqualTo(500L);
        assertThatThrownBy(() -> lineTotal(Long.MAX_VALUE, 2))
                .isInstanceOf(ArithmeticException.class);
        assertThat(orderTotal(500L, 250L)).isEqualTo(750L);
        assertThatThrownBy(() -> orderTotal(Long.MAX_VALUE, 1L))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void orderTotalIsTheExactSumOfIntegerUnitSnapshotsTimesQuantities() {
        assertThat(orderTotal(lineTotal(125L, 2), lineTotal(300L, 3)))
                .isEqualTo(1_150L);
    }

    @Test
    void paymentAmountMustExactlyMatchItsOrder() {
        assertThat(paymentMatchesOrder(1_150L, 1_150L)).isTrue();
        assertThat(paymentMatchesOrder(1_149L, 1_150L)).isFalse();
    }

    @Test
    void vnpayConvertsIntegerVndToProviderUnitsWithOverflowChecking() {
        assertThat(vnPayAmount(12_345L)).isEqualTo(1_234_500L);
        assertThatThrownBy(() -> vnPayAmount(Long.MAX_VALUE))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void momoPassesIntegerVndWithoutRoundingItAgain() {
        assertThat(momoAmount(12_345L)).isEqualTo(12_345L);
    }

    @Test
    void callbackAmountMustExactlyMatchProviderSpecificExpectedUnits() {
        assertThat(callbackMatches(12_345L, Gateway.VNPAY, 1_234_500L)).isTrue();
        assertThat(callbackMatches(12_345L, Gateway.MOMO, 12_345L)).isTrue();
        assertThat(callbackMatches(12_345L, Gateway.VNPAY, 12_345L)).isFalse();
        assertThat(callbackMatches(12_345L, Gateway.MOMO, 12_346L)).isFalse();
    }

    @Test
    void historicalCandidateRoundingFlagsSettledPaymentDifferencesWithoutChangingThem() {
        long settledPayment = 1_000L;
        long candidateOrderAmount = finalQuote(new BigDecimal("1000.50"));

        assertThat(candidateOrderAmount).isEqualTo(1_001L);
        assertThat(paymentMatchesOrder(settledPayment, candidateOrderAmount)).isFalse();
        assertThat(settledPayment).isEqualTo(1_000L);
    }

    private static long finalQuote(BigDecimal completedQuote) {
        if (completedQuote == null || completedQuote.signum() <= 0) {
            throw new IllegalArgumentException("A positive completed quote is required");
        }
        long amount = roundHalfUp(completedQuote);
        if (amount <= 0) {
            throw new IllegalArgumentException("Rounded payable amount must be positive");
        }
        return amount;
    }

    private static long roundHalfUp(BigDecimal amount) {
        if (amount == null) {
            throw new IllegalArgumentException("A decimal amount is required");
        }
        return amount.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private static long lineTotal(long unitSnapshotVnd, int quantity) {
        if (unitSnapshotVnd <= 0 || quantity <= 0) {
            throw new IllegalArgumentException("Positive snapshot and quantity are required");
        }
        return Math.multiplyExact(unitSnapshotVnd, quantity);
    }

    private static long orderTotal(long... lineTotals) {
        long total = 0L;
        for (long lineTotal : lineTotals) {
            if (lineTotal <= 0) {
                throw new IllegalArgumentException("Positive line totals are required");
            }
            total = Math.addExact(total, lineTotal);
        }
        return total;
    }

    private static boolean paymentMatchesOrder(long paymentVnd, long orderVnd) {
        return paymentVnd > 0 && orderVnd > 0 && paymentVnd == orderVnd;
    }

    private static long vnPayAmount(long amountVnd) {
        return Math.multiplyExact(amountVnd, 100L);
    }

    private static long momoAmount(long amountVnd) {
        return amountVnd;
    }

    private static boolean callbackMatches(long orderVnd, Gateway gateway, long callbackAmount) {
        long expected = switch (gateway) {
            case VNPAY -> vnPayAmount(orderVnd);
            case MOMO -> momoAmount(orderVnd);
        };
        return callbackAmount == expected;
    }

    private enum Gateway {
        VNPAY,
        MOMO
    }
}
