package com.exe.morphia3d.dto.response;

public record PriceEstimateOptionResponse(
        Integer materialId,
        String materialName,
        Integer printerId,
        String printerName,
        String configurationFingerprint) {
}
