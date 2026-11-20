package com.exe.morphia3d.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreatePriceEstimateRequest(
        @NotNull @Positive Integer modelId,
        @NotNull @Positive Integer materialId,
        @NotNull @Positive Integer printerId,
        @jakarta.validation.constraints.NotBlank String configurationFingerprint) {
}
