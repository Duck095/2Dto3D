package com.exe.morphia3d.dto.admin3d.mesh;

/** Stable identity for one configurable primitive in one product GLB asset. Names are descriptive only. */
public record MeshPrimitiveIdentity(
        Integer productId,
        Long mediaAssetId,
        Integer nodeIndex,
        Integer meshIndex,
        Integer primitiveIndex) {
}
