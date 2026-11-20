package com.exe.morphia3d.service;

import com.exe.morphia3d.dto.request.AIModelRequest;
import com.exe.morphia3d.dto.request.ModelPartRequest;
import com.exe.morphia3d.entity.MediaAsset;
import com.exe.morphia3d.entity.Model3d;
import com.exe.morphia3d.entity.ModelImage;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.MediaAssetState;
import com.exe.morphia3d.enums.MediaKind;
import com.exe.morphia3d.enums.MediaProvider;
import com.exe.morphia3d.enums.ModelStatus;
import com.exe.morphia3d.enums.ModelType;
import com.exe.morphia3d.enums.Role;
import com.exe.morphia3d.enums.UserStatus;
import com.exe.morphia3d.repository.MediaAssetRepository;
import com.exe.morphia3d.repository.Model3dRepository;
import com.exe.morphia3d.repository.ModelImageRepository;
import com.exe.morphia3d.repository.UserRepository;
import com.exe.morphia3d.security.UserPrincipal;
import com.exe.morphia3d.service.impl.Model3dServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

@SpringBootTest(properties = "app.features.ai.enabled=true")
@ActiveProfiles("test")
class AIModelPersistenceServiceTest {

    @Autowired private AIModelPersistenceService persistence;
    @MockitoSpyBean private MediaAssetLifecycleService lifecycle;
    @Autowired private MediaAssetRepository assets;
    @Autowired private Model3dRepository models;
    @Autowired private ModelImageRepository images;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Model3dServiceImpl modelService;

    private User owner;
    private final List<Long> fixtureAssetIds = new ArrayList<>();

    @BeforeEach
    void createOwner() {
        fixtureAssetIds.clear();
        owner = users.saveAndFlush(User.builder().name("AI owner").email("ai-owner-" + UUID.randomUUID()
                + "@example.com").password("encoded").role(Role.USER).status(UserStatus.ACTIVE).build());
    }

    @AfterEach
    void cleanOwnFixtures() {
        if (owner == null || owner.getId() == null) return;
        Integer ownerId = owner.getId();
        jdbc.update("DELETE FROM model_images WHERE model_id IN (SELECT model_id FROM model_3d WHERE user_id = ?)", ownerId);
        jdbc.update("DELETE FROM model_parts WHERE model_id IN (SELECT model_id FROM model_3d WHERE user_id = ?)", ownerId);
        jdbc.update("DELETE FROM model_3d WHERE user_id = ?", ownerId);
        fixtureAssetIds.forEach(id -> jdbc.update("DELETE FROM media_asset WHERE id = ?", id));
        users.deleteById(ownerId);
    }

    @Test
    void persistsAiModelImagesPartsAndAttachedAssetsFromTrustedOperation() {
        MediaAssetOperationLease operation = readyOperation();
        AIModelRequest request = requestWithForgedPaths();

        var response = persistence.persist(new UserPrincipal(owner), request, operation);

        Model3d saved = models.findById(response.getId()).orElseThrow();
        List<ModelImage> savedImages = images.findByModel_IdOrderByIsPrimaryDescIdAsc(saved.getId());
        MediaAsset modelAsset = assets.findById(operation.mediaAssetIds().get(0)).orElseThrow();
        assertThat(saved.getSourceType()).isEqualTo(ModelType.AI_MODEL);
        assertThat(saved.getStatus()).isEqualTo(ModelStatus.PENDING);
        assertThat(saved.getUser().getId()).isEqualTo(owner.getId());
        assertThat(saved.getFilePath3d()).isEqualTo(modelAsset.getPublicId()).isNotEqualTo("forged/model.glb");
        assertThat(saved.getMediaAsset().getId()).isEqualTo(modelAsset.getId());
        assertThat(savedImages).hasSize(2);
        assertThat(savedImages).extracting(ModelImage::getImagePath)
                .containsExactlyElementsOf(operation.mediaAssetIds().subList(1, 3).stream()
                        .map(id -> assets.findById(id).orElseThrow().getPublicId()).toList());
        assertThat(savedImages).extracting(ModelImage::getIsPrimary).containsExactly(true, false);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_parts WHERE model_id = ?", Integer.class,
                saved.getId())).isEqualTo(1);
        assertThat(response.getModelParts()).hasSize(1);
        assertThat(assets.findAllById(operation.mediaAssetIds())).extracting(MediaAsset::getState)
                .containsOnly(MediaAssetState.ATTACHED);
        assertThat(response.getFilePath3d()).isEqualTo(saved.getFilePath3d());
        assertThat(response.getModelImages()).extracting("imagePath")
                .containsExactlyElementsOf(savedImages.stream().map(ModelImage::getImagePath).toList());
    }

    @Test
    void attachFailureRollsBackAiModelImagesPartsAndAssetStates() {
        MediaAssetOperationLease operation = readyOperation();
        doThrow(new IllegalStateException("controlled attachment failure"))
                .when(lifecycle).attach(eq(operation), eq(operation.mediaAssetIds()));

        assertThatThrownBy(() -> persistence.persist(new UserPrincipal(owner), requestWithForgedPaths(), operation))
                .isInstanceOf(MediaPersistenceException.class)
                .satisfies(error -> assertThat(((MediaPersistenceException) error).isSafeToCompensate()).isTrue());

        assertThat(models.findAll().stream().noneMatch(model -> model.getUser() != null
                && owner.getId().equals(model.getUser().getId()))).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_images WHERE model_id IN "
                + "(SELECT model_id FROM model_3d WHERE user_id = ?)", Integer.class, owner.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_parts WHERE model_id IN "
                + "(SELECT model_id FROM model_3d WHERE user_id = ?)", Integer.class, owner.getId())).isZero();
        assertThat(assets.findAllById(operation.mediaAssetIds())).extracting(MediaAsset::getState)
                .containsOnly(MediaAssetState.READY);
    }

    @Test
    void ownerCanReadLegacyAiPathsAndAnotherUserCannot() {
        Model3d legacy = models.saveAndFlush(Model3d.builder().sourceType(ModelType.AI_MODEL).user(owner)
                .name("Legacy AI").filePath3d("legacy-ai/model-id").build());
        legacy.getImages().add(ModelImage.builder().model(legacy).imagePath("legacy-ai/image-id")
                .isPrimary(true).build());
        legacy = models.saveAndFlush(legacy);
        Integer legacyId = legacy.getId();

        var response = modelService.getAIModelById(owner.getId(), legacyId);

        assertThat(response.getFilePath3d()).isEqualTo("legacy-ai/model-id");
        assertThat(response.getModelImages()).extracting("imagePath").containsExactly("legacy-ai/image-id");
        assertThatThrownBy(() -> modelService.getAIModelById(owner.getId() + 1000, legacyId))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    private MediaAssetOperationLease readyOperation() {
        MediaAssetOperationLease operation = lifecycle.createUploadIntents(UUID.randomUUID(), List.of(
                new MediaAssetIntentSpec(MediaKind.MODEL_3D, "glb", 12),
                new MediaAssetIntentSpec(MediaKind.IMAGE, "png", 68),
                new MediaAssetIntentSpec(MediaKind.IMAGE, "webp", 68)));
        fixtureAssetIds.addAll(operation.mediaAssetIds());
        for (Long id : operation.mediaAssetIds()) {
            MediaAsset asset = assets.findById(id).orElseThrow();
            MediaUploadIntent intent = lifecycle.claimUpload(operation, id);
            String resource = asset.getMediaKind() == MediaKind.IMAGE ? "image" : "raw";
            String suffix = asset.getMediaKind() == MediaKind.IMAGE ? "." + asset.getFormat() : "";
            String url = "https://res.cloudinary.com/" + asset.getCloudName() + "/" + resource
                    + "/upload/v1/" + intent.publicId() + suffix;
            lifecycle.recordUpload(intent, new MediaUploadResult(MediaProvider.CLOUDINARY, asset.getCloudName(),
                    "asset-" + id, intent.publicId(), asset.getMediaKind(), resource, "upload", asset.getFormat(),
                    asset.getByteCount(), 1, url));
        }
        return operation;
    }

    private AIModelRequest requestWithForgedPaths() {
        AIModelRequest request = new AIModelRequest();
        request.setOwnerId(999999);
        request.setName("AI lamp");
        request.setSlug("ai-lamp");
        request.setDescription("persisted AI metadata");
        request.setFilePath3d("forged/model.glb");
        request.setCategory("lighting");
        request.setSizeXMm(12.0);
        request.setSizeYMm(13.0);
        request.setSizeZMm(14.0);
        request.setWeight(1.5);
        request.setImages(List.of(com.exe.morphia3d.dto.request.ModelImageRequest.builder()
                .imagePath("forged/image.png").isPrimary(false).build()));
        ModelPartRequest part = new ModelPartRequest();
        part.setPartName("shade");
        part.setPartKey("shade-main");
        request.setModelParts(List.of(part));
        return request;
    }
}
