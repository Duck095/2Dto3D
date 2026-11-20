package com.exe.morphia3d.dto.admin3d.mesh;

import java.util.List;
import java.util.UUID;

/** Response shape shared by GET and successful PUT mesh-configuration operations. */
public record MeshConfigurationResponse(
        Integer productId,
        Long mediaAssetId,
        UUID structureSnapshotId,
        Long revision,
        boolean configured,
        Integer materialId,
        List<Mesh> meshes) {

    public MeshConfigurationResponse {
        meshes = meshes == null ? null : List.copyOf(meshes);
    }

    public record Mesh(Integer nodeIndex, Integer meshIndex, Integer primitiveIndex,
                       String originalName, boolean customizable,
                       Selection defaultSelection, List<Selection> allowedSelections) {
        public Mesh {
            allowedSelections = allowedSelections == null ? null : List.copyOf(allowedSelections);
        }
    }

    public record Selection(Integer materialId, Integer colorId) {
    }
}
