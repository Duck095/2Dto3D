package com.exe.morphia3d.service;

import com.exe.morphia3d.dto.request.DesignerModelRequest;
import com.exe.morphia3d.dto.request.ModelPartRequest;
import com.exe.morphia3d.entity.MediaAsset;
import com.exe.morphia3d.entity.Model3d;
import com.exe.morphia3d.entity.ModelImage;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.MediaAssetState;
import com.exe.morphia3d.enums.MediaKind;
import com.exe.morphia3d.enums.MediaProvider;
import com.exe.morphia3d.enums.ModelStatus;
import com.exe.morphia3d.enums.Role;
import com.exe.morphia3d.enums.UserStatus;
import com.exe.morphia3d.repository.MediaAssetRepository;
import com.exe.morphia3d.repository.Model3dRepository;
import com.exe.morphia3d.repository.ModelImageRepository;
import com.exe.morphia3d.repository.ProductGlbStructureSnapshotRepository;
import com.exe.morphia3d.repository.ProductMeshConfigurationRevisionRepository;
import com.exe.morphia3d.repository.UserRepository;
import com.exe.morphia3d.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.ArrayList;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
@ActiveProfiles("test")
class DesignerModelPersistenceServiceTest {

    @Autowired private DesignerModelPersistenceService persistence;
    @MockitoSpyBean private MediaAssetLifecycleService lifecycle;
    @MockitoBean private ProductGlbStructureSnapshotRepository structureSnapshots;
    @MockitoBean private ProductMeshConfigurationRevisionRepository configurationRevisions;
    @Autowired private MediaAssetRepository assets;
    @Autowired private Model3dRepository models;
    @Autowired private ModelImageRepository images;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;

    private User owner;
    private final List<Long> fixtureAssetIds = new ArrayList<>();

    @BeforeEach
    void createFixtureOwner() {
        fixtureAssetIds.clear();
        owner = users.saveAndFlush(User.builder().name("Designer").email("designer-" + UUID.randomUUID()
                + "@example.com").password("encoded").role(Role.DESIGNER).status(UserStatus.ACTIVE).build());
    }

    @org.junit.jupiter.api.AfterEach
    void removeOnlyThisTestFixtures() {
        if (owner == null || owner.getId() == null) {
            return;
        }
        Integer ownerId = owner.getId();
        jdbc.update("DELETE FROM model_images WHERE model_id IN (SELECT model_id FROM model_3d WHERE user_id = ?)", ownerId);
        jdbc.update("DELETE FROM model_parts WHERE model_id IN (SELECT model_id FROM model_3d WHERE user_id = ?)", ownerId);
        jdbc.update("DELETE FROM model_3d WHERE user_id = ?", ownerId);
        for (Long assetId : fixtureAssetIds) {
            jdbc.update("DELETE FROM media_asset WHERE id = ?", assetId);
        }
        users.deleteById(ownerId);
    }

    @Test
    void persistsPendingModelAndImagesWithTrustedPublicIdsAndFirstImagePrimary() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        UUID operationId = UUID.randomUUID();
        MediaAssetOperationLease operation = lifecycle.createUploadIntents(operationId, List.of(
                new MediaAssetIntentSpec(MediaKind.MODEL_3D, "glb", 12),
                new MediaAssetIntentSpec(MediaKind.IMAGE, "png", 4),
                new MediaAssetIntentSpec(MediaKind.IMAGE, "webp", 5)));
        fixtureAssetIds.addAll(operation.mediaAssetIds());
        markAllReady(operation);
        DesignerModelRequest metadata = new DesignerModelRequest();
        metadata.setDesignerId(999);
        metadata.setFilePath3d("client-controlled/model");
        metadata.setName("Desk lamp");
        metadata.setSlug("desk-lamp");
        metadata.setImages(List.of(
                com.exe.morphia3d.dto.request.ModelImageRequest.builder()
                        .imagePath("client-controlled/primary").isPrimary(false).build(),
                com.exe.morphia3d.dto.request.ModelImageRequest.builder()
                        .imagePath("client-controlled/secondary").isPrimary(true).build()));
        ModelPartRequest part = new ModelPartRequest();
        part.setPartName("shade");
        part.setPartKey("shade-main");
        metadata.setModelParts(List.of(part));

        var response = persistence.persist(new UserPrincipal(owner), metadata, operation);

        Model3d saved = models.findById(response.getId()).orElseThrow();
        List<ModelImage> savedImages = images.findByModel_IdOrderByIsPrimaryDescIdAsc(saved.getId());
        MediaAsset modelAsset = assets.findById(operation.mediaAssetIds().get(0)).orElseThrow();
        assertThat(saved.getUser().getId()).isEqualTo(owner.getId());
        assertThat(saved.getStatus()).isEqualTo(ModelStatus.PENDING);
        assertThat(saved.getFilePath3d()).isEqualTo(modelAsset.getPublicId());
        assertThat(saved.getFilePath3d()).startsWith("product-media/models/").endsWith(".glb");
        assertThat(saved.getMediaAsset().getId()).isEqualTo(modelAsset.getId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_parts WHERE model_id = ?", Integer.class,
                saved.getId())).isEqualTo(1);
        assertThat(savedImages).hasSize(2);
        assertThat(savedImages).extracting(ModelImage::getImagePath)
                .containsExactly("product-media/images/" + operationId + "/" + operation.mediaAssetIds().get(1),
                        "product-media/images/" + operationId + "/" + operation.mediaAssetIds().get(2));
        assertThat(savedImages).extracting(ModelImage::getIsPrimary).containsExactly(true, false);
        assertThat(jdbc.queryForList("SELECT a.state FROM model_images i JOIN media_asset a "
                        + "ON a.id = i.media_asset_id WHERE i.model_id = ? ORDER BY i.image_id",
                String.class, saved.getId())).containsOnly(MediaAssetState.ATTACHED.name());
        assertThat(assets.findAllById(operation.mediaAssetIds())).extracting(MediaAsset::getState)
                .containsOnly(MediaAssetState.ATTACHED);
        assertThat(response.getFilePath3d()).isEqualTo(saved.getFilePath3d());
        assertThat(response.getModelImages()).extracting("imagePath")
                .containsExactlyElementsOf(savedImages.stream().map(ModelImage::getImagePath).toList());
    }

    @Test
    void attachFailureRollsBackModelImagesPartsAndMediaStateTogether() {
        MediaAssetOperationLease operation = newOperationWithReadyAssets();
        DesignerModelRequest metadata = completeMetadata();
        int imageCountBefore = jdbc.queryForObject("SELECT COUNT(*) FROM model_images", Integer.class);
        int partCountBefore = jdbc.queryForObject("SELECT COUNT(*) FROM model_parts", Integer.class);
        var attachFailure = new IllegalStateException("controlled attach failure");
        doThrow(attachFailure).when(lifecycle).attach(eq(operation), eq(operation.mediaAssetIds()));

        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThatThrownBy(() -> persistence.persist(new UserPrincipal(owner), metadata, operation))
                .isInstanceOf(MediaPersistenceException.class)
                .satisfies(failure -> assertThat(((MediaPersistenceException) failure).isSafeToCompensate()).isTrue());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_3d WHERE user_id = ?", Integer.class,
                owner.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_images", Integer.class)).isEqualTo(imageCountBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_parts", Integer.class)).isEqualTo(partCountBefore);
        assertThat(assets.findAllById(operation.mediaAssetIds())).extracting(MediaAsset::getState)
                .containsOnly(MediaAssetState.READY);
    }

    private MediaAssetOperationLease newOperationWithReadyAssets() {
        MediaAssetOperationLease operation = lifecycle.createUploadIntents(UUID.randomUUID(), List.of(
                new MediaAssetIntentSpec(MediaKind.MODEL_3D, "glb", 12),
                new MediaAssetIntentSpec(MediaKind.IMAGE, "png", 4)));
        fixtureAssetIds.addAll(operation.mediaAssetIds());
        markAllReady(operation);
        return operation;
    }

    private DesignerModelRequest completeMetadata() {
        DesignerModelRequest metadata = new DesignerModelRequest();
        metadata.setName("Rollback chair");
        metadata.setModelParts(List.of(partRequest()));
        return metadata;
    }

    private ModelPartRequest partRequest() {
        ModelPartRequest part = new ModelPartRequest();
        part.setPartName("seat");
        part.setPartKey("seat-main");
        return part;
    }

    private void markAllReady(MediaAssetOperationLease operation) {
        for (Long id : operation.mediaAssetIds()) {
            MediaAsset asset = assets.findById(id).orElseThrow();
            MediaUploadIntent intent = lifecycle.claimUpload(operation, id);
            MediaKind kind = asset.getMediaKind();
            String resource = kind == MediaKind.IMAGE ? "image" : "raw";
            String format = asset.getFormat();
            String suffix = kind == MediaKind.IMAGE ? "." + format : "";
            String url = "https://res.cloudinary.com/" + asset.getCloudName() + "/" + resource
                    + "/upload/v1/" + intent.publicId() + suffix;
            lifecycle.recordUpload(intent, new MediaUploadResult(MediaProvider.CLOUDINARY, asset.getCloudName(),
                    "asset-" + id, intent.publicId(), kind, resource, "upload", format,
                    asset.getByteCount(), 1, url));
        }
    }
}
