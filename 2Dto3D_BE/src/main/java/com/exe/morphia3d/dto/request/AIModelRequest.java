package com.exe.morphia3d.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AIModelRequest {

    private Integer ownerId;

    private String name;

    private String slug;

    private String description;

    private String filePath3d;

    private Double sizeXMm;

    private Double sizeYMm;

    private Double sizeZMm;

    private Double weight;

    private List<ModelPartRequest> modelParts;

    private List<ModelImageRequest> images;

    private String category;
}
