package com.exe.morphia3d.service.admin3d.mesh;

import com.exe.morphia3d.dto.admin3d.mesh.MeshConfigurationRequest;
import com.exe.morphia3d.dto.admin3d.mesh.MeshPrimitiveIdentity;
import com.exe.morphia3d.service.glb.GlbStructure;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Pure request/structure validation. Catalog compatibility and revision CAS require a database transaction. */
@Component
public final class MeshConfigurationValidator {
    private static final int MAX_OCCURRENCES = 100_000;
    private static final int MAX_ALLOWED_COLORS_PER_OCCURRENCE = 100_000;
    private static final long MAX_TOTAL_ALLOWED_COLORS = 100_000L;

    public void validate(Integer productId, Long currentMediaAssetId, UUID expectedStructureSnapshotId,
                         MeshConfigurationRequest request, GlbStructure structure) {
        require(productId != null && productId > 0, "productId must be positive");
        require(currentMediaAssetId != null && currentMediaAssetId > 0, "current mediaAssetId must be positive");
        require(expectedStructureSnapshotId != null, "structureSnapshotId is required");
        require(request != null, "request is required");
        require(request.mediaAssetId() != null && request.mediaAssetId() > 0, "mediaAssetId must be positive");
        require(currentMediaAssetId.equals(request.mediaAssetId()), "mediaAssetId is stale");
        require(request.structureSnapshotId() != null, "structureSnapshotId is required");
        require(expectedStructureSnapshotId.equals(request.structureSnapshotId()), "structureSnapshotId is stale");
        require(request.expectedRevision() != null && request.expectedRevision() >= 0
                        && request.expectedRevision() < Long.MAX_VALUE,
                "expectedRevision must be non-negative and leave room for the next revision");
        require(request.materialId() != null && request.materialId() > 0, "materialId must be positive");
        require(structure != null, "stored structure is required");
        require(structure.primitiveOccurrences() != null
                        && structure.primitiveOccurrences().size() <= MAX_OCCURRENCES,
                "stored structure occurrence count is invalid");
        require(request.meshes() != null, "meshes is required");
        require(request.meshes().size() <= MAX_OCCURRENCES, "too many mesh configurations");
        require(request.meshes().size() == structure.primitiveOccurrences().size(),
                "every primitive occurrence must be configured exactly once");

        Set<MeshPrimitiveIdentity> expected = new HashSet<>(capacity(structure.primitiveOccurrences().size()));
        for (GlbStructure.PrimitiveOccurrence occurrence : structure.primitiveOccurrences()) {
            require(occurrence != null, "stored structure contains a null occurrence");
            MeshPrimitiveIdentity identity = identity(productId, request.mediaAssetId(), occurrence.nodeIndex(),
                    occurrence.meshIndex(), occurrence.primitiveIndex());
            require(validIndices(identity), "stored structure contains an invalid primitive identity");
            require(expected.add(identity), "stored structure contains a duplicate primitive identity");
        }

        Set<MeshPrimitiveIdentity> seen = new HashSet<>(capacity(request.meshes().size()));
        long totalAllowedColors = 0L;
        for (MeshConfigurationRequest.Mesh mesh : request.meshes()) {
            require(mesh != null, "meshes cannot contain null entries");
            MeshPrimitiveIdentity identity = mesh.identity(productId, request.mediaAssetId());
            require(validIndices(identity), "mesh identity indices must be non-negative");
            require(expected.contains(identity), "unknown primitive identity");
            require(seen.add(identity), "duplicate primitive identity");
            require(mesh.allowedColorIds() != null
                            && mesh.allowedColorIds().size() <= MAX_ALLOWED_COLORS_PER_OCCURRENCE,
                    "too many allowed colors");
            totalAllowedColors = Math.addExact(totalAllowedColors, mesh.allowedColorIds().size());
            require(totalAllowedColors <= MAX_TOTAL_ALLOWED_COLORS, "too many allowed color selections");
            validateSelection(mesh);
        }
        require(seen.size() == expected.size(), "missing primitive identity");
    }

    private static void validateSelection(MeshConfigurationRequest.Mesh mesh) {
        require(mesh.customizable() != null, "customizable is required");
        require(mesh.defaultColorId() != null && mesh.defaultColorId() > 0,
                "defaultColorId must be positive");
        require(mesh.allowedColorIds() != null, "allowedColorIds is required");
        require(mesh.allowedColorIds().size() <= MAX_ALLOWED_COLORS_PER_OCCURRENCE,
                "too many allowed colors");

        Set<Integer> colors = new HashSet<>(capacity(mesh.allowedColorIds().size()));
        for (Integer colorId : mesh.allowedColorIds()) {
            require(colorId != null && colorId > 0, "allowed color IDs must be positive");
            require(colors.add(colorId), "duplicate allowed color ID");
        }
        if (mesh.customizable()) {
            require(!colors.isEmpty(), "customizable occurrences require allowed colors");
            require(colors.contains(mesh.defaultColorId()), "defaultColorId must be allowed");
        } else {
            require(colors.isEmpty(), "non-customizable occurrences cannot have allowed colors");
        }
    }

    private static MeshPrimitiveIdentity identity(Integer productId, Long mediaAssetId,
                                                  Integer nodeIndex, Integer meshIndex, Integer primitiveIndex) {
        return new MeshPrimitiveIdentity(productId, mediaAssetId, nodeIndex, meshIndex, primitiveIndex);
    }

    private static boolean validIndices(MeshPrimitiveIdentity identity) {
        return identity.nodeIndex() != null && identity.nodeIndex() >= 0
                && identity.meshIndex() != null && identity.meshIndex() >= 0
                && identity.primitiveIndex() != null && identity.primitiveIndex() >= 0;
    }

    private static int capacity(int size) {
        return size < 3 ? size + 1 : (int) Math.min(Integer.MAX_VALUE - 8L, (long) Math.ceil(size / 0.75d));
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalArgumentException(message);
        }
    }
}
