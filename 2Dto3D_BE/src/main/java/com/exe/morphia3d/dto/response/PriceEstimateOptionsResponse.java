package com.exe.morphia3d.dto.response;

import java.util.List;

public record PriceEstimateOptionsResponse(List<PriceEstimateOptionResponse> options) {
    public PriceEstimateOptionsResponse { options = List.copyOf(options); }
}
