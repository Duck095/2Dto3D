package com.exe.morphia3d.domain.money;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyVndTest {

    @Test
    void roundsACompletedQuoteHalfUpBelowAtAndAboveHalf() {
        assertThat(MoneyVnd.fromCompletedQuote(new BigDecimal("123.49")).amountVnd()).isEqualTo(123L);
        assertThat(MoneyVnd.fromCompletedQuote(new BigDecimal("123.50")).amountVnd()).isEqualTo(124L);
        assertThat(MoneyVnd.fromCompletedQuote(new BigDecimal("123.51")).amountVnd()).isEqualTo(124L);
    }

    @Test
    void roundsOnlyAfterTheCompletedQuoteIsProvided() {
        BigDecimal completedQuote = new BigDecimal("0.49").add(new BigDecimal("0.49"));

        assertThat(MoneyVnd.fromCompletedQuote(completedQuote).amountVnd()).isEqualTo(1L);
    }

    @Test
    void rejectsNonPositiveOrSubDongCompletedQuotes() {
        assertThatThrownBy(() -> MoneyVnd.fromCompletedQuote(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyVnd.fromCompletedQuote(BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyVnd.fromCompletedQuote(new BigDecimal("-1.00")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyVnd.fromCompletedQuote(new BigDecimal("0.49")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyVnd.of(0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyVnd.of(-1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsQuoteValuesOutsidePositiveLongRange() {
        assertThat(MoneyVnd.fromCompletedQuote(BigDecimal.valueOf(Long.MAX_VALUE)).amountVnd())
                .isEqualTo(Long.MAX_VALUE);
        assertThatThrownBy(() -> MoneyVnd.fromCompletedQuote(new BigDecimal("9223372036854775807.50")))
                .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> MoneyVnd.fromCompletedQuote(new BigDecimal("9223372036854775808")))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void calculatesPositiveOrderItemTotalsWithCheckedMultiplication() {
        assertThat(MoneyVnd.of(125L).lineTotal(4).amountVnd()).isEqualTo(500L);
        assertThatThrownBy(() -> MoneyVnd.of(125L).lineTotal(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyVnd.of(125L).lineTotal(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyVnd.of(Long.MAX_VALUE).lineTotal(2))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void calculatesOrderTotalAsCheckedSumOfPositiveLineTotals() {
        assertThat(MoneyVnd.sumLineTotals(List.of(MoneyVnd.of(250L), MoneyVnd.of(900L))).amountVnd())
                .isEqualTo(1_150L);
        assertThatThrownBy(() -> MoneyVnd.sumLineTotals(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyVnd.sumLineTotals(List.of(MoneyVnd.of(Long.MAX_VALUE), MoneyVnd.of(1L))))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void comparesPaymentAndOrderAmountsExactly() {
        assertThat(MoneyVnd.of(1_150L).sameAmountAs(MoneyVnd.of(1_150L))).isTrue();
        assertThat(MoneyVnd.of(1_149L).sameAmountAs(MoneyVnd.of(1_150L))).isFalse();
        assertThat(MoneyVnd.of(1_150L).sameAmountAs(null)).isFalse();
    }

    @Test
    void convertsVndAtProviderBoundariesWithoutFloatingPoint() {
        assertThat(MoneyVnd.of(12_345L).vnPayProviderAmount()).isEqualTo(1_234_500L);
        assertThat(MoneyVnd.of(12_345L).momoProviderAmount()).isEqualTo(12_345L);
        assertThatThrownBy(() -> MoneyVnd.of(Long.MAX_VALUE).vnPayProviderAmount())
                .isInstanceOf(ArithmeticException.class);
    }
}
