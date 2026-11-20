package com.exe.morphia3d.service.admin3d.mesh;

import com.exe.morphia3d.dto.admin3d.mesh.MeshConfigurationRequest;
import com.exe.morphia3d.dto.admin3d.mesh.MeshConfigurationResponse;
import com.exe.morphia3d.dto.admin3d.mesh.MeshStructureResponse;
import com.exe.morphia3d.entity.MediaAsset;
import com.exe.morphia3d.entity.Model3d;
import com.exe.morphia3d.entity.ProductGlbStructureSnapshot;
import com.exe.morphia3d.enums.MediaAssetState;
import com.exe.morphia3d.enums.MediaKind;
import com.exe.morphia3d.enums.ModelType;
import com.exe.morphia3d.repository.MediaAssetRepository;
import com.exe.morphia3d.repository.Model3dRepository;
import com.exe.morphia3d.repository.ProductGlbStructureSnapshotRepository;
import com.exe.morphia3d.service.glb.GlbStructure;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AdminProductMeshService {
    private final Model3dRepository products;
    private final MediaAssetRepository mediaAssets;
    private final ProductGlbStructureSnapshotRepository snapshots;
    private final MeshConfigurationPersistenceService configurations;
    private final TransactionTemplate readTransaction;

    public AdminProductMeshService(Model3dRepository products,
                                   MediaAssetRepository mediaAssets,
                                   ProductGlbStructureSnapshotRepository snapshots,
                                   MeshConfigurationPersistenceService configurations,
                                   PlatformTransactionManager transactionManager) {
        this.products = products;
        this.mediaAssets = mediaAssets;
        this.snapshots = snapshots;
        this.configurations = configurations;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public MeshStructureResponse getStructure(Integer productId) {
        if (productId == null || productId <= 0) {
            throw new MeshConfigurationException.Invalid("productId must be positive");
        }
        try {
            return readTransaction.execute(status -> loadStructure(productId));
        } catch (MeshConfigurationException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            if (hasCorruptionCause(exception)) {
                throw new MeshConfigurationException.Conflict("GLB structure snapshot is corrupt or unsupported", exception);
            }
            throw new MeshConfigurationException.PersistenceFailure("Could not read mesh structure", exception);
        } catch (RuntimeException exception) {
            if (hasCorruptionCause(exception)) {
                throw new MeshConfigurationException.Conflict("GLB structure snapshot is corrupt or unsupported", exception);
            }
            throw exception;
        }
    }

    public MeshConfigurationResponse getConfiguration(Integer productId) {
        return configurations.getLatestConfiguration(productId);
    }

    public MeshConfigurationResponse putConfiguration(Integer productId, Integer actorId,
                                                        MeshConfigurationRequest request) {
        return configurations.putConfiguration(productId, actorId, request);
    }

    private MeshStructureResponse loadStructure(Integer productId) {
        Model3d product = products.findById(productId)
                .filter(value -> value.getSourceType() == ModelType.DESIGNER_MODEL)
                .orElseThrow(() -> new MeshConfigurationException.NotFound("Product was not found"));
        MediaAsset relation = product.getMediaAsset();
        if (relation == null || relation.getId() == null) {
            throw new MeshConfigurationException.Conflict("Current GLB media asset is not available");
        }
        MediaAsset asset = mediaAssets.findById(relation.getId())
                .orElseThrow(() -> new MeshConfigurationException.Conflict("Current GLB media asset is not available"));
        if (asset.getMediaKind() != MediaKind.MODEL_3D || asset.getState() != MediaAssetState.ATTACHED) {
            throw new MeshConfigurationException.Conflict("Current media asset is not an attached MODEL_3D asset");
        }
        ProductGlbStructureSnapshot snapshot;
        try {
            snapshot = snapshots.findFirstByProductIdAndMediaAssetIdOrderBySnapshotRevisionDesc(productId, asset.getId())
                    .orElseThrow(() -> new MeshConfigurationException.Conflict("GLB structure snapshot is required"));
        } catch (MeshConfigurationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            if (hasCorruptionCause(exception)) {
                throw new MeshConfigurationException.Conflict("GLB structure snapshot is corrupt or unsupported", exception);
            }
            throw new MeshConfigurationException.PersistenceFailure("Could not read GLB structure history", exception);
        }
        if (!snapshot.getProductId().equals(productId) || !snapshot.getMediaAssetId().equals(asset.getId())) {
            throw new MeshConfigurationException.Conflict("GLB structure snapshot identity is invalid");
        }
        final GlbStructure structure;
        try {
            structure = snapshot.readStructure();
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new MeshConfigurationException.Conflict("GLB structure snapshot is corrupt or unsupported", exception);
        }
        return map(productId, asset.getId(), snapshot, structure);
    }

    private static MeshStructureResponse map(Integer productId, Long mediaAssetId,
                                             ProductGlbStructureSnapshot snapshot, GlbStructure structure) {
        return new MeshStructureResponse(productId, mediaAssetId, snapshot.getStructureSnapshotId(),
                snapshot.getParserVersion(), structure.defaultSceneIndex(),
                structure.scenes().stream().map(scene -> new MeshStructureResponse.Scene(
                        scene.index(), scene.name(), scene.rootNodeIndices())).toList(),
                structure.nodes().stream().map(node -> new MeshStructureResponse.Node(
                        node.index(), node.name(), node.meshIndex(), node.childIndices(), node.sceneIndices())).toList(),
                structure.meshes().stream().map(mesh -> new MeshStructureResponse.Mesh(mesh.index(), mesh.name(),
                        mesh.primitives().stream().map(primitive -> new MeshStructureResponse.Primitive(
                                primitive.index(), primitive.materialIndex(), primitive.attributeNames(),
                                primitive.textureIndices())).toList())).toList(),
                structure.primitiveOccurrences().stream().map(occurrence -> new MeshStructureResponse.PrimitiveOccurrence(
                        occurrence.nodeIndex(), occurrence.meshIndex(), occurrence.primitiveIndex(),
                        occurrence.originalName(), occurrence.materialIndex())).toList(),
                structure.materials().stream().map(material -> new MeshStructureResponse.Material(
                        material.index(), material.name(), material.textureIndices())).toList(),
                structure.textures().stream().map(texture -> new MeshStructureResponse.Texture(
                        texture.index(), texture.sourceImageIndices(), texture.samplerIndex())).toList(),
                structure.images().stream().map(image -> new MeshStructureResponse.Image(
                        image.index(), image.name(), image.mimeType(), image.uriSource(), image.bufferViewIndex())).toList());
    }

    private static boolean hasCorruptionCause(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof IllegalArgumentException || current instanceof IllegalStateException) return true;
            current = current.getCause();
        }
        return false;
    }
}
