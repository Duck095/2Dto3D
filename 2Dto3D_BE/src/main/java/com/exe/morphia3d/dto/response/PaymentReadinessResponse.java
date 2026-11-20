package com.exe.morphia3d.dto.response;

import com.exe.morphia3d.enums.PaymentProvider;

import java.util.Map;

public record PaymentReadinessResponse(
        Integer orderId,
        boolean eligible,
        String blocker,
        Long productSubtotalVnd,
        Long shippingAmountVnd,
        Long payableAmountVnd,
        Map<PaymentProvider, Boolean> providerAvailability,
        boolean shippingConfirmed,
        boolean totalFrozen) {
    public PaymentReadinessResponse(Integer orderId, boolean eligible, String blocker, Long productSubtotalVnd,
            Long shippingAmountVnd, Long payableAmountVnd, Map<PaymentProvider, Boolean> providerAvailability) {
        this(orderId, eligible, blocker, productSubtotalVnd, shippingAmountVnd, payableAmountVnd,
                providerAvailability, false, false);
    }

    public PaymentReadinessResponse {
        providerAvailability = providerAvailability == null ? Map.of() : Map.copyOf(providerAvailability);
    }
}
