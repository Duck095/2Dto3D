package com.exe.morphia3d.dto.response;

import com.exe.morphia3d.enums.MaterialStatus;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class MaterialResponse {
    private Integer id;
    private String name;
    private String type;
    private Double pricePerGram;
    private Double stockQuantity;
    private String unit;
    private MaterialStatus isAvailable;
    private List<ColorResponse> colors;
}