package com.exe.morphia3d.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class ColorResponse {
    private Integer colorId;

    private String name;

    private String hexCode;
}
