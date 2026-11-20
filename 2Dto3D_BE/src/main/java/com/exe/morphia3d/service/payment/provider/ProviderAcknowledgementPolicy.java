package com.exe.morphia3d.service.payment.provider;

import java.util.OptionalInt;

public final class ProviderAcknowledgementPolicy {
    private ProviderAcknowledgementPolicy() {}

    public static String vnpay(CallbackReceiptLedgerOutcome outcome) {
        return switch (outcome) {
            case RECORDED -> "00";
            case DUPLICATE -> "02";
            case INVALID_SIGNATURE -> "97";
            case ORDER_NOT_FOUND -> "01";
            case AMOUNT_MISMATCH -> "04";
            case CONFLICT, INTERNAL_ERROR -> "99";
        };
    }

    /** MoMo's documented acknowledgement for a durably handled IPN is HTTP 204 without a body. */
    public static OptionalInt momoHttpStatus(boolean durablyHandledOrIdenticalDuplicate) {
        return durablyHandledOrIdenticalDuplicate ? OptionalInt.of(204) : OptionalInt.empty();
    }

    public enum CallbackReceiptLedgerOutcome { RECORDED, DUPLICATE, INVALID_SIGNATURE, ORDER_NOT_FOUND, AMOUNT_MISMATCH, CONFLICT, INTERNAL_ERROR }
}
