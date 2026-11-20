package com.exe.morphia3d.dto.response;

import com.exe.morphia3d.enums.ModelStatus;
import com.exe.morphia3d.enums.ModelType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ModelResponse {
    private Integer id;

    private ModelType sourceType;

    private Integer userId;

    private String slug;

    private String name;

    private String description;

    private String filePath3d;

    private Double sizeXMm;

    private Double sizeYMm;

    private Double sizeZMm;

    private Double volumeCm3;

    private List<ModelPartResponse> modelParts;

    private List<ModelImageResponse> modelImages;

    private String category;

    private ModelStatus status;

    private LocalDateTime createdAt;
}
