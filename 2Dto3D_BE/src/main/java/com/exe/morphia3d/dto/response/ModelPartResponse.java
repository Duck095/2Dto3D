package com.exe.morphia3d.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ModelPartResponse {
    private Integer id;

    private String partName;

    private String partKey;

    private ColorResponse color;
}
