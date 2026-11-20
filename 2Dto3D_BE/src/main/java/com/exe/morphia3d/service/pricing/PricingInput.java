package com.exe.morphia3d.service.pricing;

import java.math.BigDecimal;

public record PricingInput(
        BigDecimal consumedMaterialGrams,
        BigDecimal materialPricePerGramVnd,
        BigDecimal printTimeHours) {
}
