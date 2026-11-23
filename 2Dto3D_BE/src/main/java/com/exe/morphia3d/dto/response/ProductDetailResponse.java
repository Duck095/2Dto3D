package com.exe.morphia3d.dto.response;

import java.util.List;

public record ProductDetailResponse(Integer id, String name, String slug, String description, String category,
                                    List<ProductImageResponse> images, String model3dUrl, Double sizeXMm,
                                    Double sizeYMm, Double sizeZMm, List<ProductPartResponse> modelParts,
                                    List<ProductMeshAppearanceResponse> modelMeshAppearances) {
}
