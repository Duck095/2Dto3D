package com.exe.morphia3d.service.payment.provider;

public final class ProviderStatusMapping {
    private ProviderStatusMapping() {}

    public static ProviderPaymentState vnpay(String responseCode, String transactionStatus) {
        if ("00".equals(responseCode) && "00".equals(transactionStatus)) return ProviderPaymentState.SUCCEEDED;
        if ("24".equals(responseCode)) return ProviderPaymentState.CANCELED;
        if ("01".equals(responseCode) || "01".equals(transactionStatus)) return ProviderPaymentState.PENDING;
        return ProviderPaymentState.UNKNOWN;
    }

    public static ProviderPaymentState momo(int resultCode) {
        return switch (resultCode) {
            case 0 -> ProviderPaymentState.SUCCEEDED;
            case 9000 -> ProviderPaymentState.AUTHORIZED;
            case 1000, 7000, 7002 -> ProviderPaymentState.PENDING;
            case 1003 -> ProviderPaymentState.CANCELED;
            default -> ProviderPaymentState.UNKNOWN;
        };
    }
}
