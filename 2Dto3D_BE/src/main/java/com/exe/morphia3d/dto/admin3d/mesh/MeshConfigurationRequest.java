package com.exe.morphia3d.dto.admin3d.mesh;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Complete replacement request for PUT /api/admin/products/{productId}/mesh-configurations. */
public record MeshConfigurationRequest(
        Long mediaAssetId,
        UUID structureSnapshotId,
        Long expectedRevision,
        Integer materialId,
        List<Mesh> meshes) {

    public MeshConfigurationRequest {
        meshes = immutableCopy(meshes);
    }

    public record Mesh(Integer nodeIndex, Integer meshIndex, Integer primitiveIndex,
                       Boolean customizable, Integer defaultColorId, List<Integer> allowedColorIds) {
        public Mesh {
            allowedColorIds = immutableCopy(allowedColorIds);
        }

        public MeshPrimitiveIdentity identity(Integer productId, Long mediaAssetId) {
            return new MeshPrimitiveIdentity(productId, mediaAssetId, nodeIndex, meshIndex, primitiveIndex);
        }
    }

    private static <T> List<T> immutableCopy(List<T> values) {
        return values == null ? null : Collections.unmodifiableList(new ArrayList<>(values));
    }
}
