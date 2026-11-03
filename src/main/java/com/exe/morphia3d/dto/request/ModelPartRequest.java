package com.exe.morphia3d.dto.request;

import lombok.Data;

@Data
public class ModelPartRequest {
    private String partName;
    private String partKey;
    private Integer colorId;
}
