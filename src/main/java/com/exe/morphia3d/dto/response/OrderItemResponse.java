package com.exe.morphia3d.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OrderItemResponse {
    private Integer id;
    private Integer modelId;
    private Integer quantity;
    private Double priceSnapshot;
}
