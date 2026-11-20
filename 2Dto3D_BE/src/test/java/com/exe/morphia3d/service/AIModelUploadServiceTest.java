package com.exe.morphia3d.service;

import com.exe.morphia3d.dto.request.AIModelRequest;
import com.exe.morphia3d.dto.response.ModelResponse;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.MediaKind;
import com.exe.morphia3d.enums.MediaProvider;
import com.exe.morphia3d.enums.Role;
import com.exe.morphia3d.security.UserPrincipal;
import com.exe.morphia3d.feature.FeatureGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AIModelUploadServiceTest {

    @Mock private MediaStorageService storage;
    @Mock private MediaAssetLifecycleService lifecycle;
    @Mock private MediaUploadHeartbeat heartbeat;
    @Mock private AIModelPersistenceService persistence;
    @Mock private FeatureGate featureGate;

    private final MediaUploadProperties properties = new MediaUploadProperties();
    private final MediaValidationService validation = new MediaValidationService(properties);
    private AIModelUploadService service;
    private UserPrincipal principal;
    private AIModelRequest metadata;
    private MultipartFile image;
    private MultipartFile model;
    private MediaAssetOperationLease operation;
    private MediaUploadIntent modelIntent;
    private MediaUploadIntent imageIntent;

    @BeforeEach
    void setUp() {
        service = new AIModelUploadService(validation, storage, lifecycle, heartbeat, persistence, featureGate);
        principal = new UserPrincipal(User.builder().id(42).role(Role.USER).build());
        metadata = new AIModelRequest();
        metadata.setOwnerId(999);
        metadata.setName("AI lamp");
        metadata.setSlug("ai-lamp");
        metadata.setFilePath3d("attacker/model");
        metadata.setImages(List.of());
        image = file("preview.png", "image/png", pngBytes());
        model = file("shape.glb", "model/gltf-binary", validGlbBytes());
        operation = new MediaAssetOperationLease(UUID.randomUUID(), UUID.randomUUID(), "upload-owner",
                List.of(101L, 102L));
        modelIntent = new MediaUploadIntent(101L, operation.operationId(), operation.leaseToken(), MediaKind.MODEL_3D);
        imageIntent = new MediaUploadIntent(102L, operation.operationId(), operation.leaseToken(), MediaKind.IMAGE);
    }

    @Test
    void validatesEveryFileBeforeCreatingIntentsOrCallingStorage() {
        MultipartFile invalidImage = file("bad.png", "image/png", new byte[]{1, 2, 3});

        assertThatThrownBy(() -> service.create(principal, metadata, List.of(image, invalidImage), model))
                .isInstanceOf(MediaValidationException.class);

        verifyNoInteractions(lifecycle, storage, heartbeat, persistence);
    }

    @Test
    void rejectsUnsupportedMimeMalformedGlbAndImageLimitBeforeCreatingIntents() {
        assertThatThrownBy(() -> service.create(principal, metadata,
                List.of(file("preview.png", "text/plain", pngBytes())), model))
                .isInstanceOf(MediaValidationException.class);
        assertThatThrownBy(() -> service.create(principal, metadata, List.of(image),
                file("shape.glb", "model/gltf-binary", new byte[12])))
                .isInstanceOf(MediaValidationException.class);
        assertThatThrownBy(() -> service.create(principal, metadata,
                java.util.Collections.nCopies(properties.getMaxImages() + 1, image), model))
                .isInstanceOf(MediaValidationException.class);

        verifyNoInteractions(lifecycle, storage, heartbeat, persistence);
    }

    @Test
    void rejectsMetadataOutsidePersistedColumnBoundsBeforeCreatingIntents() {
        metadata.setCategory("x".repeat(101));

        assertThatThrownBy(() -> service.create(principal, metadata, List.of(image), model))
                .isInstanceOf(MediaValidationException.class)
                .satisfies(error -> assertThat(((MediaValidationException) error).getReason())
                        .isEqualTo(MediaValidationException.Reason.MALFORMED_REQUEST));

        verifyNoInteractions(lifecycle, storage, heartbeat, persistence);
    }

    @Test
    void rejectsOversizedImageAndGlbWithoutReadingContentsOrCreatingIntents() {
        MultipartFile oversizedImage = oversizedFile("large.png", properties.getImageMaxBytes() + 1);
        MultipartFile oversizedModel = oversizedFile("large.glb", properties.getModelMaxBytes() + 1);

        assertThatThrownBy(() -> service.create(principal, metadata, List.of(oversizedImage), model))
                .isInstanceOf(MediaValidationException.class)
                .satisfies(error -> assertThat(((MediaValidationException) error).getReason())
                        .isEqualTo(MediaValidationException.Reason.TOO_LARGE));
        assertThatThrownBy(() -> service.create(principal, metadata, List.of(image), oversizedModel))
                .isInstanceOf(MediaValidationException.class)
                .satisfies(error -> assertThat(((MediaValidationException) error).getReason())
                        .isEqualTo(MediaValidationException.Reason.TOO_LARGE));

        verifyNoInteractions(lifecycle, storage, heartbeat, persistence);
    }

    @Test
    void uploadsOneOperationAndPersistsUsingAuthenticatedOwnerBoundary() {
        prepareSuccessfulFlow();
        ModelResponse response = service.create(principal, metadata, List.of(image), model);

        assertThat(response.getId()).isEqualTo(700);
        verify(lifecycle).createUploadIntents(any(), org.mockito.ArgumentMatchers.argThat(specs ->
                specs.size() == 2 && specs.get(0).mediaKind() == MediaKind.MODEL_3D
                        && specs.get(1).mediaKind() == MediaKind.IMAGE));
        verify(lifecycle).recordUpload(eq(modelIntent), any());
        verify(lifecycle).recordUpload(eq(imageIntent), any());
        verify(persistence).persist(principal, metadata, operation);
        assertThat(metadata.getOwnerId()).isEqualTo(999);
        assertThat(metadata.getFilePath3d()).isEqualTo("attacker/model");
    }

    @Test
    void partialImageUploadFailureRequestsOperationCleanupAndPreservesFailure() {
        prepareSuccessfulFlow();
        RuntimeException providerFailure = new MediaStorageException(MediaStorageException.Reason.PROVIDER_UNAVAILABLE);
        doThrow(providerFailure).when(storage).upload(eq(imageIntent), any());

        assertThatThrownBy(() -> service.create(principal, metadata, List.of(image), model))
                .isSameAs(providerFailure);

        verify(lifecycle).requestCleanup(operation, operation.mediaAssetIds());
        verify(persistence, never()).persist(any(), any(), any());
    }

    @Test
    void knownRollbackRequestsCleanupButAmbiguousCommitDoesNot() {
        prepareSuccessfulFlow();
        when(persistence.persist(principal, metadata, operation)).thenThrow(
                MediaPersistenceException.rolledBack(new IllegalStateException("rolled back")));
        assertThatThrownBy(() -> service.create(principal, metadata, List.of(image), model))
                .isInstanceOf(MediaPersistenceException.class);
        verify(lifecycle).requestCleanup(operation, operation.mediaAssetIds());

        org.mockito.Mockito.reset(lifecycle, storage, heartbeat, persistence);
        prepareSuccessfulFlow();
        when(persistence.persist(principal, metadata, operation)).thenThrow(
                MediaPersistenceException.outcomeUnknown(new IllegalStateException("commit uncertain")));
        assertThatThrownBy(() -> service.create(principal, metadata, List.of(image), model))
                .isInstanceOf(MediaPersistenceException.class);
        verify(lifecycle, never()).requestCleanup(any(), anyCollection());
    }

    @Test
    void cleanupFailureIsSuppressedOnOriginalUploadFailure() {
        prepareSuccessfulFlow();
        RuntimeException providerFailure = new MediaStorageException(MediaStorageException.Reason.PROVIDER_UNAVAILABLE);
        doThrow(providerFailure).when(storage).upload(eq(modelIntent), any());
        doThrow(new IllegalStateException("cleanup unavailable"))
                .when(lifecycle).requestCleanup(operation, operation.mediaAssetIds());

        assertThatThrownBy(() -> service.create(principal, metadata, List.of(image), model))
                .isSameAs(providerFailure)
                .satisfies(error -> assertThat(error.getSuppressed()).hasSize(1));
    }

    private MultipartFile file(String name, String mime, byte[] bytes) {
        return new MockMultipartFile("file", name, mime, bytes);
    }

    private MultipartFile oversizedFile(String name, long size) {
        MultipartFile file = org.mockito.Mockito.mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(size);
        when(file.getOriginalFilename()).thenReturn(name);
        return file;
    }

    private void prepareSuccessfulFlow() {
        Mockito.lenient().when(lifecycle.createUploadIntents(any(), anyList())).thenReturn(operation);
        Mockito.lenient().when(lifecycle.claimUpload(operation, 101L)).thenReturn(modelIntent);
        Mockito.lenient().when(lifecycle.claimUpload(operation, 102L)).thenReturn(imageIntent);
        Mockito.lenient().when(heartbeat.during(any(), any())).thenAnswer(call ->
                ((java.util.function.Supplier<?>) call.getArgument(1)).get());
        Mockito.lenient().when(storage.upload(eq(modelIntent), any())).thenAnswer(call -> result(modelIntent,
                (ValidatedMedia) call.getArgument(1), "raw", "glb"));
        Mockito.lenient().when(storage.upload(eq(imageIntent), any())).thenAnswer(call -> result(imageIntent,
                (ValidatedMedia) call.getArgument(1), "image", "png"));
        Mockito.lenient().when(persistence.persist(principal, metadata, operation))
                .thenReturn(ModelResponse.builder().id(700).build());
    }

    private MediaUploadResult result(MediaUploadIntent intent, ValidatedMedia media, String resource, String format) {
        String suffix = intent.mediaKind() == MediaKind.MODEL_3D ? "" : "." + format;
        return new MediaUploadResult(MediaProvider.CLOUDINARY, "test-cloud", "asset-" + intent.mediaAssetId(),
                intent.publicId(), intent.mediaKind(), resource, "upload", format, media.byteCount(), 1,
                "https://res.cloudinary.com/test-cloud/" + resource + "/upload/v1/" + intent.publicId() + suffix);
    }

    private byte[] pngBytes() {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private byte[] validGlbBytes() {
        return new byte[]{'g', 'l', 'T', 'F', 2, 0, 0, 0, 12, 0, 0, 0};
    }
}
