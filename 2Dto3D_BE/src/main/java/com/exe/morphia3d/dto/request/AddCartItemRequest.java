package com.exe.morphia3d.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record AddCartItemRequest(
        @NotNull @Positive Integer modelId,
        @NotNull @Positive Integer estimateId,
        @NotNull @Min(1) Integer quantity) {
}
