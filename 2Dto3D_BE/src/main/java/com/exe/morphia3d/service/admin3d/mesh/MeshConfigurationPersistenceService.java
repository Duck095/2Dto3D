package com.exe.morphia3d.service.admin3d.mesh;

import com.exe.morphia3d.dto.admin3d.mesh.MeshConfigurationRequest;
import com.exe.morphia3d.dto.admin3d.mesh.MeshConfigurationResponse;
import com.exe.morphia3d.entity.MediaAsset;
import com.exe.morphia3d.entity.Material;
import com.exe.morphia3d.entity.MaterialColor;
import com.exe.morphia3d.entity.Model3d;
import com.exe.morphia3d.entity.ProductGlbStructureSnapshot;
import com.exe.morphia3d.entity.ProductMeshConfigurationRevision;
import com.exe.morphia3d.enums.MaterialStatus;
import com.exe.morphia3d.enums.MediaAssetState;
import com.exe.morphia3d.enums.MediaKind;
import com.exe.morphia3d.enums.ModelType;
import com.exe.morphia3d.repository.MaterialColorRepository;
import com.exe.morphia3d.repository.MaterialRepository;
import com.exe.morphia3d.repository.MediaAssetRepository;
import com.exe.morphia3d.repository.Model3dRepository;
import com.exe.morphia3d.repository.ProductGlbStructureSnapshotRepository;
import com.exe.morphia3d.repository.ProductMeshConfigurationRevisionRepository;
import com.exe.morphia3d.service.glb.GlbStructure;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class MeshConfigurationPersistenceService {
    private final Model3dRepository products;
    private final MediaAssetRepository mediaAssets;
    private final MaterialRepository materials;
    private final MaterialColorRepository materialColors;
    private final ProductGlbStructureSnapshotRepository snapshots;
    private final ProductMeshConfigurationRevisionRepository configurations;
    private final MeshConfigurationValidator validator;
    private final Admin3dLockGateway lockGateway;
    private final EntityManager entityManager;

    public MeshConfigurationPersistenceService(Model3dRepository products,
                                               MediaAssetRepository mediaAssets,
                                               MaterialRepository materials,
                                               MaterialColorRepository materialColors,
                                               ProductGlbStructureSnapshotRepository snapshots,
                                               ProductMeshConfigurationRevisionRepository configurations,
                                               MeshConfigurationValidator validator,
                                               Admin3dLockGateway lockGateway,
                                               EntityManager entityManager) {
        this.products = products;
        this.mediaAssets = mediaAssets;
        this.materials = materials;
        this.materialColors = materialColors;
        this.snapshots = snapshots;
        this.configurations = configurations;
        this.validator = validator;
        this.lockGateway = lockGateway;
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MeshConfigurationResponse getLatestConfiguration(Integer productId) {
        if (productId == null || productId <= 0) throw new MeshConfigurationException.Invalid("productId must be positive");
        Model3d product = products.findById(productId)
                .orElseThrow(() -> new MeshConfigurationException.NotFound("Product was not found"));
        requireDesignerProduct(product);
        MediaAsset currentAsset = requireCurrentAttachedAsset(product);
        ProductGlbStructureSnapshot currentSnapshot = requireLatestSnapshot(productId, currentAsset.getId());
        GlbStructure currentStructure = readStructure(currentSnapshot);

        ProductMeshConfigurationRevision latest = readLatestConfiguration(productId, currentAsset.getId());
        if (latest == null) {
            return unconfigured(productId, currentAsset.getId(), currentSnapshot.getStructureSnapshotId(), 0L);
        }

        ProductGlbStructureSnapshot configurationSnapshot = requireSnapshotIdentity(
                latest, productId, currentAsset.getId());
        GlbStructure configurationStructure = readStructure(configurationSnapshot);
        List<MeshConfigurationRequest.Mesh> storedMeshes = readConfigurationMeshes(latest.getConfigurationJson());
        validateStoredConfiguration(latest, configurationSnapshot, configurationStructure, storedMeshes);

        if (!latest.getStructureSnapshotId().equals(currentSnapshot.getStructureSnapshotId())) {
            return unconfigured(productId, currentAsset.getId(), currentSnapshot.getStructureSnapshotId(), latest.getConfigRevision());
        }
        return configured(productId, currentAsset.getId(), currentSnapshot.getStructureSnapshotId(),
                latest.getConfigRevision(), latest.getMaterialId(), currentStructure, storedMeshes);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MeshConfigurationResponse putConfiguration(Integer productId, Integer trustedCreatedBy,
                                                       MeshConfigurationRequest request) {
        if (productId == null || productId <= 0) throw new MeshConfigurationException.Invalid("productId must be positive");
        if (trustedCreatedBy == null || trustedCreatedBy <= 0) {
            throw new MeshConfigurationException.Invalid("trusted createdBy is required");
        }
        if (request == null || request.mediaAssetId() == null || request.mediaAssetId() <= 0
                || request.structureSnapshotId() == null) {
            throw new MeshConfigurationException.Invalid("mediaAssetId and structureSnapshotId are required");
        }

        // Lock order: product, every asset in its operation (ordered by ID), material, associations, INSERT.
        lockGateway.lockProduct(productId);
        Object[] productIdentity = readLockedProductIdentity(productId);
        String sourceType = productIdentity[0] == null ? null : productIdentity[0].toString();
        if (!ModelType.DESIGNER_MODEL.name().equals(sourceType)) {
            throw new MeshConfigurationException.NotFound("Product was not found");
        }
        if (!(productIdentity[1] instanceof Number mediaId)) {
            throw new MeshConfigurationException.Conflict("Product has no attached GLB media asset");
        }
        Long currentMediaAssetId = mediaId.longValue();
        MediaAsset productAsset = mediaAssets.findById(currentMediaAssetId)
                .orElseThrow(() -> new MeshConfigurationException.Conflict("Product media asset is missing"));
        entityManager.refresh(productAsset);
        if (productAsset.getOperationId() == null) {
            throw new MeshConfigurationException.Conflict("Product media asset has no operation identity");
        }
        int lockedOperationMemberCount = lockGateway.lockMediaOperation(productAsset.getOperationId());
        List<MediaAsset> operationAssets = mediaAssets.findAllByOperationIdOrderByCreatedAtAscIdAsc(productAsset.getOperationId());
        if (operationAssets.size() != lockedOperationMemberCount) {
            throw new MeshConfigurationException.Conflict("Media operation membership changed while acquiring locks");
        }
        operationAssets.forEach(entityManager::refresh);
        if (operationAssets.isEmpty()) throw new MeshConfigurationException.Conflict("Media operation has no assets");
        rejectCleanupOperationMembers(operationAssets);
        MediaAsset selectedAsset = operationAssets.stream()
                .filter(asset -> asset.getId().equals(currentMediaAssetId))
                .findFirst()
                .orElseThrow(() -> new MeshConfigurationException.Conflict("Current product asset is missing from its media operation"));
        if (!request.mediaAssetId().equals(currentMediaAssetId)) {
            throw new MeshConfigurationException.Conflict("mediaAssetId is stale");
        }
        requireModel3dAttached(selectedAsset);

        ProductGlbStructureSnapshot currentSnapshot = requireLatestSnapshot(productId, currentMediaAssetId);
        GlbStructure currentStructure = readStructure(currentSnapshot);
        ProductMeshConfigurationRevision latest = readLatestConfiguration(productId, currentMediaAssetId);
        long latestRevision = latest == null ? 0L : latest.getConfigRevision();
        if (latestRevision == Long.MAX_VALUE) {
            throw new MeshConfigurationException.Conflict("Configuration revision counter is exhausted");
        }
        if (request.expectedRevision() == null || request.expectedRevision() < 0) {
            throw new MeshConfigurationException.Invalid("expectedRevision must be non-negative");
        }
        if (request.expectedRevision() != latestRevision) {
            throw new MeshConfigurationException.Conflict("expectedRevision is stale");
        }
        if (!request.structureSnapshotId().equals(currentSnapshot.getStructureSnapshotId())) {
            throw new MeshConfigurationException.Conflict("structureSnapshotId is stale");
        }
        if (request.expectedRevision() == Long.MAX_VALUE) {
            throw new MeshConfigurationException.Conflict("Configuration revision counter is exhausted");
        }
        if (latest != null) validateLatestStoredConfiguration(latest, productId, currentMediaAssetId);

        try {
            validator.validate(productId, currentMediaAssetId, currentSnapshot.getStructureSnapshotId(), request, currentStructure);
        } catch (IllegalArgumentException exception) {
            throw new MeshConfigurationException.Invalid(exception.getMessage(), exception);
        }

        lockGateway.lockMaterial(request.materialId());
        Material material = materials.findById(request.materialId())
                .orElseThrow(() -> new MeshConfigurationException.MaterialNotFound("Material was not found"));
        entityManager.refresh(material);
        if (material.getIsAvailable() != MaterialStatus.AVAILABLE) {
            throw new MeshConfigurationException.Conflict("Material is unavailable");
        }
        Set<Integer> compatibleColors = new HashSet<>();
        for (MaterialColor association : materialColors.findAllByMaterial_Id(material.getId())) {
            compatibleColors.add(association.getId().getColorId());
        }
        requireCompatibleColors(request, compatibleColors);

        long newRevision = latestRevision + 1L; // latest MAX_VALUE is rejected above, before addition.
        ProductMeshConfigurationRevision revision = new ProductMeshConfigurationRevision(
                UUID.randomUUID(), productId, currentMediaAssetId, currentSnapshot.getStructureSnapshotId(),
                newRevision, material.getId(), toConfigurationJson(request), Instant.now(), trustedCreatedBy);
        try {
            configurations.save(revision);
            entityManager.flush();
        } catch (RuntimeException exception) {
            String state = findSqlState(exception);
            if ("23505".equals(state) || "23503".equals(state)) {
                throw new MeshConfigurationException.Conflict("Configuration revision conflicted with current database state", exception);
            }
            if ("23514".equals(state) || "23502".equals(state)) {
                throw new MeshConfigurationException.Invalid("Configuration failed database validation", exception);
            }
            throw new MeshConfigurationException.PersistenceFailure("Could not persist mesh configuration revision", exception);
        }
        return configured(productId, currentMediaAssetId, currentSnapshot.getStructureSnapshotId(),
                newRevision, material.getId(), currentStructure, request.meshes());
    }

    private ProductMeshConfigurationRevision readLatestConfiguration(Integer productId, Long mediaAssetId) {
        try {
            return configurations.findFirstByProductIdAndMediaAssetIdOrderByConfigRevisionDesc(productId, mediaAssetId)
                    .orElse(null);
        } catch (RuntimeException exception) {
            throw historyReadFailure("Latest configuration history", exception);
        }
    }

    private Object[] readLockedProductIdentity(Integer productId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(
                        "SELECT source_type, media_asset_id FROM public.model_3d WHERE model_id = :productId")
                .setParameter("productId", productId)
                .getResultList();
        if (rows.isEmpty()) throw new MeshConfigurationException.NotFound("Product was not found");
        return rows.get(0);
    }

    private ProductGlbStructureSnapshot requireLatestSnapshot(Integer productId, Long mediaAssetId) {
        ProductGlbStructureSnapshot snapshot;
        try {
            snapshot = snapshots.findFirstByProductIdAndMediaAssetIdOrderBySnapshotRevisionDesc(productId, mediaAssetId)
                    .orElseThrow(() -> new MeshConfigurationException.Conflict("GLB structure snapshot is required"));
        } catch (RuntimeException exception) {
            throw historyReadFailure("Latest GLB structure history", exception);
        }
        readStructure(snapshot);
        return snapshot;
    }

    private ProductGlbStructureSnapshot requireSnapshotIdentity(ProductMeshConfigurationRevision configuration,
                                                                 Integer productId, Long mediaAssetId) {
        ProductGlbStructureSnapshot snapshot;
        try {
            snapshot = snapshots.findByStructureSnapshotId(configuration.getStructureSnapshotId())
                    .orElseThrow(() -> new MeshConfigurationException.Conflict("Configuration structure snapshot is missing"));
        } catch (RuntimeException exception) {
            throw historyReadFailure("Configuration structure history", exception);
        }
        if (!snapshot.getProductId().equals(productId) || !snapshot.getMediaAssetId().equals(mediaAssetId)) {
            throw new MeshConfigurationException.Conflict("Configuration references a different product or media asset");
        }
        return snapshot;
    }

    private void validateLatestStoredConfiguration(ProductMeshConfigurationRevision latest,
                                                   Integer productId, Long mediaAssetId) {
        ProductGlbStructureSnapshot storedSnapshot = requireSnapshotIdentity(latest, productId, mediaAssetId);
        GlbStructure storedStructure = readStructure(storedSnapshot);
        List<MeshConfigurationRequest.Mesh> storedMeshes = readConfigurationMeshes(latest.getConfigurationJson());
        validateStoredConfiguration(latest, storedSnapshot, storedStructure, storedMeshes);
    }

    private void validateStoredConfiguration(ProductMeshConfigurationRevision latest,
                                             ProductGlbStructureSnapshot snapshot,
                                             GlbStructure structure,
                                             List<MeshConfigurationRequest.Mesh> meshes) {
        MeshConfigurationRequest storedRequest = new MeshConfigurationRequest(
                latest.getMediaAssetId(), snapshot.getStructureSnapshotId(), 0L, latest.getMaterialId(), meshes);
        try {
            validator.validate(latest.getProductId(), latest.getMediaAssetId(), snapshot.getStructureSnapshotId(),
                    storedRequest, structure);
        } catch (IllegalArgumentException exception) {
            throw new MeshConfigurationException.Conflict("Latest configuration history is corrupt", exception);
        }
    }

    private GlbStructure readStructure(ProductGlbStructureSnapshot snapshot) {
        try {
            return snapshot.readStructure();
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new MeshConfigurationException.Conflict("GLB structure snapshot is corrupt or unsupported", exception);
        }
    }

    private static List<MeshConfigurationRequest.Mesh> readConfigurationMeshes(JsonNode json) {
        try {
            ArrayNode array = (ArrayNode) json.path("meshes");
            List<MeshConfigurationRequest.Mesh> result = new ArrayList<>(array.size());
            for (JsonNode mesh : array) {
                List<Integer> allowed = new ArrayList<>(mesh.path("allowedColorIds").size());
                mesh.path("allowedColorIds").forEach(color -> allowed.add(color.intValue()));
                result.add(new MeshConfigurationRequest.Mesh(mesh.path("nodeIndex").intValue(),
                        mesh.path("meshIndex").intValue(), mesh.path("primitiveIndex").intValue(),
                        mesh.path("customizable").booleanValue(), mesh.path("defaultColorId").intValue(), allowed));
            }
            return List.copyOf(result);
        } catch (RuntimeException exception) {
            throw new MeshConfigurationException.Conflict("Latest configuration JSON is corrupt", exception);
        }
    }

    private static ObjectNode toConfigurationJson(MeshConfigurationRequest request) {
        ObjectNode root = JsonNodeFactory.instance.objectNode()
                .put("schemaVersion", ProductMeshConfigurationRevision.CONFIGURATION_JSON_SCHEMA_VERSION);
        ArrayNode meshes = root.putArray("meshes");
        for (MeshConfigurationRequest.Mesh mesh : request.meshes()) {
            ObjectNode value = meshes.addObject()
                    .put("nodeIndex", mesh.nodeIndex())
                    .put("meshIndex", mesh.meshIndex())
                    .put("primitiveIndex", mesh.primitiveIndex())
                    .put("customizable", mesh.customizable())
                    .put("defaultColorId", mesh.defaultColorId());
            ArrayNode colors = value.putArray("allowedColorIds");
            mesh.allowedColorIds().forEach(colors::add);
        }
        return root;
    }

    private static MeshConfigurationResponse configured(Integer productId, Long mediaAssetId, UUID snapshotId,
                                                         long revision, Integer materialId, GlbStructure structure,
                                                         List<MeshConfigurationRequest.Mesh> meshes) {
        Map<String, String> names = new HashMap<>();
        for (GlbStructure.PrimitiveOccurrence occurrence : structure.primitiveOccurrences()) {
            names.put(identity(occurrence.nodeIndex(), occurrence.meshIndex(), occurrence.primitiveIndex()),
                    occurrence.originalName());
        }
        List<MeshConfigurationResponse.Mesh> responseMeshes = meshes.stream().map(mesh -> {
            String identity = identity(mesh.nodeIndex(), mesh.meshIndex(), mesh.primitiveIndex());
            List<MeshConfigurationResponse.Selection> allowed = mesh.allowedColorIds().stream()
                    .map(color -> new MeshConfigurationResponse.Selection(materialId, color)).toList();
            return new MeshConfigurationResponse.Mesh(mesh.nodeIndex(), mesh.meshIndex(), mesh.primitiveIndex(),
                    names.get(identity), mesh.customizable(),
                    new MeshConfigurationResponse.Selection(materialId, mesh.defaultColorId()), allowed);
        }).toList();
        return new MeshConfigurationResponse(productId, mediaAssetId, snapshotId, revision, true, materialId, responseMeshes);
    }

    private static MeshConfigurationResponse unconfigured(Integer productId, Long mediaAssetId, UUID snapshotId,
                                                           long revision) {
        return new MeshConfigurationResponse(productId, mediaAssetId, snapshotId, revision, false, null, List.of());
    }

    private static String identity(int node, int mesh, int primitive) {
        return node + ":" + mesh + ":" + primitive;
    }

    private static void requireCompatibleColors(MeshConfigurationRequest request, Set<Integer> compatibleColors) {
        for (MeshConfigurationRequest.Mesh mesh : request.meshes()) {
            if (!compatibleColors.contains(mesh.defaultColorId())) {
                throw new MeshConfigurationException.Invalid("Default color is not compatible with the selected material");
            }
            for (Integer colorId : mesh.allowedColorIds()) {
                if (!compatibleColors.contains(colorId)) {
                    throw new MeshConfigurationException.Invalid("Allowed color is not compatible with the selected material");
                }
            }
        }
    }

    private MediaAsset requireCurrentAttachedAsset(Model3d product) {
        MediaAsset relation = product.getMediaAsset();
        if (relation == null || relation.getId() == null) {
            throw new MeshConfigurationException.Conflict("Product has no current GLB media asset");
        }
        MediaAsset asset = mediaAssets.findById(relation.getId())
                .orElseThrow(() -> new MeshConfigurationException.Conflict("Current GLB media asset is missing"));
        requireModel3dAttached(asset);
        return asset;
    }

    private static void requireModel3dAttached(MediaAsset asset) {
        if (asset.getMediaKind() != MediaKind.MODEL_3D || asset.getState() != MediaAssetState.ATTACHED) {
            throw new MeshConfigurationException.Conflict("Current media asset is not an attached MODEL_3D asset");
        }
    }

    private static void requireDesignerProduct(Model3d product) {
        if (product.getSourceType() != ModelType.DESIGNER_MODEL) {
            throw new MeshConfigurationException.NotFound("Product was not found");
        }
    }

    private static void rejectCleanupOperationMembers(List<MediaAsset> assets) {
        if (assets.stream().anyMatch(asset -> asset.getState() == MediaAssetState.CLEANUP_PENDING
                || asset.getState() == MediaAssetState.CLEANUP_LEASED
                || asset.getState() == MediaAssetState.DELETED)) {
            throw new MeshConfigurationException.Conflict("Media operation contains an asset in cleanup or deleted state");
        }
    }

    private static String findSqlState(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof SQLException sqlException) return sqlException.getSQLState();
            current = current.getCause();
        }
        return null;
    }

    private static MeshConfigurationException historyReadFailure(String resource, RuntimeException exception) {
        if (exception instanceof MeshConfigurationException domainFailure) return domainFailure;
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof IllegalStateException || cause instanceof IllegalArgumentException) {
                return new MeshConfigurationException.Conflict(resource + " is corrupt or unsupported", exception);
            }
            cause = cause.getCause();
        }
        return new MeshConfigurationException.PersistenceFailure("Could not read " + resource, exception);
    }
}
