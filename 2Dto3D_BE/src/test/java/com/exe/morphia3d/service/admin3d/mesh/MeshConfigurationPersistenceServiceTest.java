package com.exe.morphia3d.service.admin3d.mesh;

import com.exe.morphia3d.dto.admin3d.mesh.MeshConfigurationRequest;
import com.exe.morphia3d.dto.admin3d.mesh.MeshConfigurationResponse;
import com.exe.morphia3d.entity.MediaAsset;
import com.exe.morphia3d.entity.Material;
import com.exe.morphia3d.entity.MaterialColor;
import com.exe.morphia3d.entity.MaterialColorId;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MeshConfigurationPersistenceServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int PRODUCT_ID = 41;
    private static final int CREATOR_ID = 8;
    private static final int MATERIAL_ID = 12;
    private static final int COLOR_ID = 7;
    private static final long MEDIA_ID = 101L;
    private static final UUID OPERATION_ID = UUID.fromString("8ae62f10-df91-4da2-bb17-4d04716c9361");
    private static final UUID SNAPSHOT_ID = UUID.fromString("fb591aa6-6d5e-4ec2-976c-108e58a46fa8");

    @Mock private Model3dRepository products;
    @Mock private MediaAssetRepository mediaAssets;
    @Mock private MaterialRepository materials;
    @Mock private MaterialColorRepository materialColors;
    @Mock private ProductGlbStructureSnapshotRepository snapshots;
    @Mock private ProductMeshConfigurationRevisionRepository configurations;
    @Mock private Admin3dLockGateway lockGateway;
    @Mock private EntityManager entityManager;
    @Mock private Query productIdentityQuery;

    private MeshConfigurationPersistenceService service;
    private Model3d product;
    private MediaAsset asset;
    private ProductGlbStructureSnapshot snapshot;

    @BeforeEach
    void setUp() {
        service = new MeshConfigurationPersistenceService(products, mediaAssets, materials, materialColors,
                snapshots, configurations, new MeshConfigurationValidator(), lockGateway, entityManager);
        asset = MediaAsset.builder().id(MEDIA_ID).operationId(OPERATION_ID).mediaKind(MediaKind.MODEL_3D)
                .state(MediaAssetState.ATTACHED).build();
        product = Model3d.builder().id(PRODUCT_ID).sourceType(ModelType.DESIGNER_MODEL).mediaAsset(asset).build();
        snapshot = new ProductGlbStructureSnapshot(SNAPSHOT_ID, PRODUCT_ID, MEDIA_ID, 1L,
                ProductGlbStructureSnapshot.CURRENT_PARSER_VERSION, structureJson(), Instant.parse("2026-01-01T00:00:00Z"), CREATOR_ID);
    }

    @Test
    void getReturnsUnconfiguredRevisionZeroWhenNoConfigurationExists() {
        when(products.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(mediaAssets.findById(MEDIA_ID)).thenReturn(Optional.of(asset));
        when(snapshots.findFirstByProductIdAndMediaAssetIdOrderBySnapshotRevisionDesc(PRODUCT_ID, MEDIA_ID))
                .thenReturn(Optional.of(snapshot));
        when(configurations.findFirstByProductIdAndMediaAssetIdOrderByConfigRevisionDesc(PRODUCT_ID, MEDIA_ID))
                .thenReturn(Optional.empty());

        MeshConfigurationResponse response = service.getLatestConfiguration(PRODUCT_ID);

        assertThat(response.configured()).isFalse();
        assertThat(response.revision()).isZero();
        assertThat(response.materialId()).isNull();
        assertThat(response.meshes()).isEmpty();
        assertThat(response.structureSnapshotId()).isEqualTo(SNAPSHOT_ID);
    }

    @Test
    void putAppendsFirstRevisionAndUsesTrustedCreatorAfterLockingInRequiredOrder() {
        stubProductIdentity(ModelType.DESIGNER_MODEL.name());
        when(lockGateway.lockMediaOperation(OPERATION_ID)).thenReturn(1);
        when(mediaAssets.findAllByOperationIdOrderByCreatedAtAscIdAsc(OPERATION_ID)).thenReturn(List.of(asset));
        when(snapshots.findFirstByProductIdAndMediaAssetIdOrderBySnapshotRevisionDesc(PRODUCT_ID, MEDIA_ID))
                .thenReturn(Optional.of(snapshot));
        when(configurations.findFirstByProductIdAndMediaAssetIdOrderByConfigRevisionDesc(PRODUCT_ID, MEDIA_ID))
                .thenReturn(Optional.empty());
        Material material = Material.builder().id(MATERIAL_ID).isAvailable(MaterialStatus.AVAILABLE).build();
        when(materials.findById(MATERIAL_ID)).thenReturn(Optional.of(material));
        MaterialColor association = org.mockito.Mockito.mock(MaterialColor.class);
        when(association.getId()).thenReturn(new MaterialColorId(MATERIAL_ID, COLOR_ID));
        when(materialColors.findAllByMaterial_Id(MATERIAL_ID)).thenReturn(List.of(association));
        when(configurations.save(any(ProductMeshConfigurationRevision.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MeshConfigurationResponse response = service.putConfiguration(PRODUCT_ID, CREATOR_ID,
                request(0L, SNAPSHOT_ID, List.of(mesh(COLOR_ID, List.of(COLOR_ID)))));

        assertThat(response.configured()).isTrue();
        assertThat(response.revision()).isEqualTo(1L);
        assertThat(response.materialId()).isEqualTo(MATERIAL_ID);
        verify(lockGateway).lockProduct(PRODUCT_ID);
        verify(lockGateway).lockMediaOperation(OPERATION_ID);
        verify(lockGateway).lockMaterial(MATERIAL_ID);
        InOrder order = inOrder(lockGateway, products, mediaAssets, snapshots, configurations, materials, materialColors, entityManager);
        order.verify(lockGateway).lockProduct(PRODUCT_ID);
        order.verify(entityManager).createNativeQuery(org.mockito.ArgumentMatchers.anyString());
        order.verify(mediaAssets).findById(MEDIA_ID);
        order.verify(entityManager).refresh(asset);
        order.verify(lockGateway).lockMediaOperation(OPERATION_ID);
        order.verify(mediaAssets).findAllByOperationIdOrderByCreatedAtAscIdAsc(OPERATION_ID);
        order.verify(entityManager).refresh(asset);
        order.verify(snapshots).findFirstByProductIdAndMediaAssetIdOrderBySnapshotRevisionDesc(PRODUCT_ID, MEDIA_ID);
        order.verify(configurations).findFirstByProductIdAndMediaAssetIdOrderByConfigRevisionDesc(PRODUCT_ID, MEDIA_ID);
        order.verify(lockGateway).lockMaterial(MATERIAL_ID);
        order.verify(materials).findById(MATERIAL_ID);
        order.verify(entityManager).refresh(material);
        order.verify(materialColors).findAllByMaterial_Id(MATERIAL_ID);
        order.verify(configurations).save(any(ProductMeshConfigurationRevision.class));
        verify(entityManager).flush();
    }

    @Test
    void staleExpectedRevisionConflictsBeforeMaterialLockOrInsert() {
        stubProductIdentity(ModelType.DESIGNER_MODEL.name());
        when(lockGateway.lockMediaOperation(OPERATION_ID)).thenReturn(1);
        when(mediaAssets.findAllByOperationIdOrderByCreatedAtAscIdAsc(OPERATION_ID)).thenReturn(List.of(asset));
        when(snapshots.findFirstByProductIdAndMediaAssetIdOrderBySnapshotRevisionDesc(PRODUCT_ID, MEDIA_ID))
                .thenReturn(Optional.of(snapshot));
        when(configurations.findFirstByProductIdAndMediaAssetIdOrderByConfigRevisionDesc(PRODUCT_ID, MEDIA_ID))
                .thenReturn(Optional.of(configuration(3L, SNAPSHOT_ID)));

        assertThatThrownBy(() -> service.putConfiguration(PRODUCT_ID, CREATOR_ID,
                request(2L, SNAPSHOT_ID, List.of(mesh(COLOR_ID, List.of(COLOR_ID))))))
                .isInstanceOf(MeshConfigurationException.Conflict.class);

        verify(materials, never()).findById(any());
        verify(configurations, never()).save(any());
    }

    @Test
    void rejectsColorNoLongerAssociatedWithLockedMaterialWithoutInsertingRevision() {
        stubProductIdentity(ModelType.DESIGNER_MODEL.name());
        when(lockGateway.lockMediaOperation(OPERATION_ID)).thenReturn(1);
        when(mediaAssets.findAllByOperationIdOrderByCreatedAtAscIdAsc(OPERATION_ID)).thenReturn(List.of(asset));
        when(snapshots.findFirstByProductIdAndMediaAssetIdOrderBySnapshotRevisionDesc(PRODUCT_ID, MEDIA_ID))
                .thenReturn(Optional.of(snapshot));
        when(configurations.findFirstByProductIdAndMediaAssetIdOrderByConfigRevisionDesc(PRODUCT_ID, MEDIA_ID))
                .thenReturn(Optional.empty());
        when(materials.findById(MATERIAL_ID)).thenReturn(Optional.of(
                Material.builder().id(MATERIAL_ID).isAvailable(MaterialStatus.AVAILABLE).build()));
        when(materialColors.findAllByMaterial_Id(MATERIAL_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.putConfiguration(PRODUCT_ID, CREATOR_ID,
                request(0L, SNAPSHOT_ID, List.of(mesh(COLOR_ID, List.of(COLOR_ID))))))
                .isInstanceOf(MeshConfigurationException.Invalid.class);

        verify(configurations, never()).save(any());
        verify(entityManager, never()).flush();
    }

    @Test
    void missingMaterialFromRepositoryUsesDedicatedDomainExceptionWithoutInsertingRevision() {
        stubProductIdentity(ModelType.DESIGNER_MODEL.name());
        when(lockGateway.lockMediaOperation(OPERATION_ID)).thenReturn(1);
        when(mediaAssets.findAllByOperationIdOrderByCreatedAtAscIdAsc(OPERATION_ID)).thenReturn(List.of(asset));
        when(snapshots.findFirstByProductIdAndMediaAssetIdOrderBySnapshotRevisionDesc(PRODUCT_ID, MEDIA_ID))
                .thenReturn(Optional.of(snapshot));
        when(configurations.findFirstByProductIdAndMediaAssetIdOrderByConfigRevisionDesc(PRODUCT_ID, MEDIA_ID))
                .thenReturn(Optional.empty());
        when(materials.findById(MATERIAL_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.putConfiguration(PRODUCT_ID, CREATOR_ID,
                request(0L, SNAPSHOT_ID, List.of(mesh(COLOR_ID, List.of(COLOR_ID))))))
                .isInstanceOf(MeshConfigurationException.MaterialNotFound.class);

        verify(configurations, never()).save(any());
        verify(entityManager, never()).flush();
    }

    @Test
    void getFailsClosedWhenLatestConfigurationUsesUnsupportedJsonSchema() {
        when(products.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(mediaAssets.findById(MEDIA_ID)).thenReturn(Optional.of(asset));
        when(snapshots.findFirstByProductIdAndMediaAssetIdOrderBySnapshotRevisionDesc(PRODUCT_ID, MEDIA_ID))
                .thenReturn(Optional.of(snapshot));
        when(configurations.findFirstByProductIdAndMediaAssetIdOrderByConfigRevisionDesc(PRODUCT_ID, MEDIA_ID))
                .thenThrow(new IllegalStateException("Unsupported mesh configuration JSON schema version: 99"));

        assertThatThrownBy(() -> service.getLatestConfiguration(PRODUCT_ID))
                .isInstanceOf(MeshConfigurationException.Conflict.class)
                .hasMessageContaining("corrupt or unsupported");
    }

    @Test
    void latestHistoryDatabaseReadFailureIsClassifiedAsPersistenceFailure() {
        when(products.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(mediaAssets.findById(MEDIA_ID)).thenReturn(Optional.of(asset));
        when(snapshots.findFirstByProductIdAndMediaAssetIdOrderBySnapshotRevisionDesc(PRODUCT_ID, MEDIA_ID))
                .thenReturn(Optional.of(snapshot));
        when(configurations.findFirstByProductIdAndMediaAssetIdOrderByConfigRevisionDesc(PRODUCT_ID, MEDIA_ID))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThatThrownBy(() -> service.getLatestConfiguration(PRODUCT_ID))
                .isInstanceOf(MeshConfigurationException.PersistenceFailure.class);
    }

    @Test
    void getTreatsWrongProductTypeAsNotFound() {
        Model3d wrongType = Model3d.builder().id(PRODUCT_ID).sourceType(ModelType.AI_MODEL).build();
        when(products.findById(PRODUCT_ID)).thenReturn(Optional.of(wrongType));

        assertThatThrownBy(() -> service.getLatestConfiguration(PRODUCT_ID))
                .isInstanceOf(MeshConfigurationException.NotFound.class);
    }

    @Test
    void putTreatsWrongProductTypeAsNotFound() {
        stubProductIdentity(ModelType.AI_MODEL.name());

        assertThatThrownBy(() -> service.putConfiguration(PRODUCT_ID, CREATOR_ID,
                request(0L, SNAPSHOT_ID, List.of(mesh(COLOR_ID, List.of(COLOR_ID))))))
                .isInstanceOf(MeshConfigurationException.NotFound.class);
    }

    private static ProductMeshConfigurationRevision configuration(long revision, UUID snapshotId) {
        return new ProductMeshConfigurationRevision(UUID.randomUUID(), PRODUCT_ID, MEDIA_ID, snapshotId,
                revision, MATERIAL_ID, configurationJson(COLOR_ID), Instant.parse("2026-01-01T00:00:00Z"), CREATOR_ID);
    }

    private void stubProductIdentity(String sourceType) {
        when(entityManager.createNativeQuery(org.mockito.ArgumentMatchers.anyString())).thenReturn(productIdentityQuery);
        when(productIdentityQuery.setParameter("productId", PRODUCT_ID)).thenReturn(productIdentityQuery);
        when(productIdentityQuery.getResultList()).thenReturn(List.<Object[]>of(new Object[]{sourceType, MEDIA_ID}));
        if (ModelType.DESIGNER_MODEL.name().equals(sourceType)) {
            when(mediaAssets.findById(MEDIA_ID)).thenReturn(Optional.of(asset));
        }
    }

    private static MeshConfigurationRequest request(long revision, UUID snapshotId,
                                                    List<MeshConfigurationRequest.Mesh> meshes) {
        return new MeshConfigurationRequest(MEDIA_ID, snapshotId, revision, MATERIAL_ID, meshes);
    }

    private static MeshConfigurationRequest.Mesh mesh(int defaultColor, List<Integer> allowed) {
        return new MeshConfigurationRequest.Mesh(0, 0, 0, true, defaultColor, allowed);
    }

    private static GlbStructure structure() {
        return new GlbStructure(-1, List.of(),
                List.of(new GlbStructure.Node(0, "Node", 0, List.of(), List.of())),
                List.of(new GlbStructure.Mesh(0, "Mesh", List.of(
                        new GlbStructure.Primitive(0, null, List.of("POSITION"), List.of())))),
                List.of(), List.of(), List.of(),
                List.of(new GlbStructure.PrimitiveOccurrence(0, 0, 0, "Node", null)));
    }

    private static JsonNode structureJson() { return JSON.valueToTree(structure()); }

    private static JsonNode configurationJson(int colorId) {
        return JSON.createObjectNode().put("schemaVersion", 1)
                .set("meshes", JSON.createArrayNode().add(JSON.createObjectNode()
                        .put("nodeIndex", 0).put("meshIndex", 0).put("primitiveIndex", 0)
                        .put("customizable", true).put("defaultColorId", colorId)
                        .set("allowedColorIds", JSON.createArrayNode().add(colorId))));
    }
}
