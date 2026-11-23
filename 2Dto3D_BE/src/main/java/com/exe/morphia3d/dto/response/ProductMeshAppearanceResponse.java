package com.exe.morphia3d.dto.response;

import java.util.List;

public record ProductMeshAppearanceResponse(
        int nodeIndex,
        int meshIndex,
        int primitiveIndex,
        boolean customizable,
        ProductMeshColorResponse defaultColor,
        List<ProductMeshColorResponse> allowedColors,
        int materialId,
        String materialName) {

    public record ProductMeshColorResponse(int id, String name, String hexCode) { }
}
