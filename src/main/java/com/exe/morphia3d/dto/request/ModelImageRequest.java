package com.exe.morphia3d.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ModelImageRequest {
    private String imagePath;

    private Boolean isPrimary;
}
