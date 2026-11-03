package com.exe.morphia3d.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaterialRequest {
    private String name;

    private String type;

    private List<ColorQuantityRequest> colors; // colorId + quantity

    private Double pricePerGram;

    private Double stockQuantity;

    private String unit;
}
