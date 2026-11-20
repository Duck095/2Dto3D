package com.exe.morphia3d.domain.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;

/** Immutable whole-dong payable amount. It is intentionally independent of persistence and gateways. */
public record MoneyVnd(long amountVnd) {

    public MoneyVnd {
        if (amountVnd <= 0) {
            throw new IllegalArgumentException("VND payable amount must be positive");
        }
    }

    public static MoneyVnd of(long amountVnd) {
        return new MoneyVnd(amountVnd);
    }

    /** Converts a fully calculated quote to whole dong, rounding once with HALF_UP. */
    public static MoneyVnd fromCompletedQuote(BigDecimal completedQuote) {
        if (completedQuote == null || completedQuote.signum() <= 0) {
            throw new IllegalArgumentException("A positive completed quote is required");
        }

        long wholeDong = completedQuote.setScale(0, RoundingMode.HALF_UP).longValueExact();
        return new MoneyVnd(wholeDong);
    }

    public MoneyVnd lineTotal(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Order item quantity must be positive");
        }
        return new MoneyVnd(Math.multiplyExact(amountVnd, (long) quantity));
    }

    public static MoneyVnd sumLineTotals(Collection<MoneyVnd> lineTotals) {
        if (lineTotals == null || lineTotals.isEmpty()) {
            throw new IllegalArgumentException("At least one order item total is required");
        }

        long total = 0L;
        for (MoneyVnd lineTotal : lineTotals) {
            if (lineTotal == null) {
                throw new IllegalArgumentException("Order item total is required");
            }
            total = Math.addExact(total, lineTotal.amountVnd);
        }
        return new MoneyVnd(total);
    }

    public boolean sameAmountAs(MoneyVnd other) {
        return other != null && amountVnd == other.amountVnd;
    }

    /** Converts VND to VNPay's provider units at the adapter boundary. */
    public long vnPayProviderAmount() {
        return Math.multiplyExact(amountVnd, 100L);
    }

    /** MoMo receives the whole-dong VND amount unchanged. */
    public long momoProviderAmount() {
        return amountVnd;
    }
}
