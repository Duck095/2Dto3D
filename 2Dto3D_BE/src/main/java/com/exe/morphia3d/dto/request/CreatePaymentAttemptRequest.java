package com.exe.morphia3d.dto.request;

import com.exe.morphia3d.enums.PaymentMethod;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreatePaymentAttemptRequest(
        @NotNull @Positive Integer orderId,
        @NotNull PaymentMethod method) {
}
