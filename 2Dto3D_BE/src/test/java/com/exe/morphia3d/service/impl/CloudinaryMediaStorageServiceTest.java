package com.exe.morphia3d.service.impl;

import com.cloudinary.Cloudinary;
import com.cloudinary.Uploader;
import com.cloudinary.utils.ObjectUtils;
import com.exe.morphia3d.enums.MediaKind;
import com.exe.morphia3d.enums.MediaProvider;
import com.exe.morphia3d.service.DeleteResult;
import com.exe.morphia3d.service.MediaAssetIdentity;
import com.exe.morphia3d.service.MediaStorageException;
import com.exe.morphia3d.service.MediaStorageProperties;
import com.exe.morphia3d.service.MediaUploadIntent;
import com.exe.morphia3d.service.MediaUploadResult;
import com.exe.morphia3d.service.ValidatedMedia;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CloudinaryMediaStorageServiceTest {

    private static final String CLOUD_NAME = "test-cloud";
    private static final long VERSION = 1_761_800_000L;

    private Uploader uploader;
    private Cloudinary cloudinary;
    private CloudinaryMediaStorageService storage;

    @BeforeEach
    void setUp() {
        cloudinary = spy(new Cloudinary(ObjectUtils.asMap(
                "cloud_name", CLOUD_NAME,
                "api_key", "test-key",
                "api_secret", "test-secret",
                "secure", true)));
        uploader = mock(Uploader.class);
        doReturn(uploader).when(cloudinary).uploader();
        storage = new CloudinaryMediaStorageService(cloudinary, new MediaStorageProperties());
    }

    @Test
    void uploadsImageWithImageUploadType() throws Exception {
        MediaUploadIntent intent = intent(MediaKind.IMAGE);
        ValidatedMedia media = validated(MediaKind.IMAGE, "png", new byte[]{1, 2, 3});
        Map<String, Object> response = providerResponse(intent, "image", "upload", "png", 3L,
                secureUrl(intent, "image", "upload", "png"));
        when(uploader.upload(any(InputStream.class), any(Map.class))).thenReturn(response);

        MediaUploadResult result = storage.upload(intent, media);

        assertThat(result.mediaKind()).isEqualTo(MediaKind.IMAGE);
        assertThat(result.publicId()).isEqualTo(intent.publicId());
        assertThat(result.publicId()).doesNotContain("client-name");
        assertThat(result.resourceType()).isEqualTo("image");
        assertThat(result.deliveryType()).isEqualTo("upload");
        assertThat(result.format()).isEqualTo("png");
        assertThat(result.byteCount()).isEqualTo(3);
        assertThat(result.providerVersion()).isEqualTo(VERSION);
        assertThat(result.secureUrl()).startsWith("https://res.cloudinary.com/");

        var options = uploadOptions();
        assertThat(options).containsEntry("public_id", intent.publicId())
                .containsEntry("resource_type", "image")
                .containsEntry("type", "upload")
                .containsEntry("overwrite", false)
                .containsEntry("use_filename", false)
                .containsEntry("unique_filename", false)
                .containsEntry("connect_timeout", 5_000)
                .containsEntry("timeout", 60_000)
                .containsEntry("connection_request_timeout", 5_000);
        assertThat(uploadedObject()).isInstanceOf(InputStream.class);
        assertThat(options).doesNotContainKey("asset_folder").doesNotContainKey("folder");
    }

    @Test
    void uploadsGlbWithRawTypeAndGlbPublicId() throws Exception {
        MediaUploadIntent intent = intent(MediaKind.MODEL_3D);
        ValidatedMedia media = validated(MediaKind.MODEL_3D, "glb", new byte[12]);
        Map<String, Object> response = providerResponse(intent, "raw", "upload", "glb", 12L,
                secureUrl(intent, "raw", "upload", "glb"));
        when(uploader.upload(any(InputStream.class), any(Map.class))).thenReturn(response);

        MediaUploadResult result = storage.upload(intent, media);

        assertThat(intent.publicId()).endsWith(".glb");
        assertThat(result.publicId()).endsWith(".glb");
        assertThat(result.resourceType()).isEqualTo("raw");
        assertThat(uploadOptions()).containsEntry("resource_type", "raw")
                .containsEntry("type", "upload")
                .doesNotContainKey("asset_folder")
                .doesNotContainKey("folder");
        assertThat(uploadedObject()).isInstanceOf(InputStream.class);
    }

    @Test
    void uploadsThreeMfAsAuthenticatedRawAssetUnderPrintFiles() throws Exception {
        MediaUploadIntent intent = intent(MediaKind.PRINT_3MF);
        ValidatedMedia media = validated(MediaKind.PRINT_3MF, "3mf", new byte[]{1, 2, 3});
        when(uploader.upload(any(InputStream.class), any(Map.class))).thenReturn(providerResponse(intent,
                "raw", "authenticated", "3mf", 3L, secureUrl(intent, "raw", "authenticated", "3mf")));

        MediaUploadResult result = storage.upload(intent, media);

        assertThat(intent.publicId()).matches("product-media/print-files/[0-9a-f-]{36}/\\d+\\.3mf");
        assertThat(result.deliveryType()).isEqualTo("authenticated");
        assertThat(result.resourceType()).isEqualTo("raw");
        assertThat(uploadOptions()).containsEntry("public_id", intent.publicId())
                .containsEntry("resource_type", "raw")
                .containsEntry("type", "authenticated");
    }

    @Test
    void rejectsThreeMfProviderResponseWithPublicDeliveryUrl() throws Exception {
        MediaUploadIntent intent = intent(MediaKind.PRINT_3MF);
        ValidatedMedia media = validated(MediaKind.PRINT_3MF, "3mf", new byte[]{1, 2, 3});
        when(uploader.upload(any(InputStream.class), any(Map.class))).thenReturn(providerResponse(intent,
                "raw", "upload", "3mf", 3L, secureUrl(intent, "raw", "upload", "3mf")));

        assertThatThrownBy(() -> storage.upload(intent, media))
                .isInstanceOf(MediaStorageException.class);
    }

    @Test
    void qaLivePngUsesImagesAssetFolderWithoutChangingGeneratedPublicId() throws Exception {
        storage = new CloudinaryMediaStorageService(cloudinary, new MediaStorageProperties(),
                CloudinaryMediaStorageService.FolderPolicy.QA_DYNAMIC_PRODUCT_MEDIA);
        MediaUploadIntent intent = intent(MediaKind.IMAGE);
        ValidatedMedia media = validated(MediaKind.IMAGE, "png", new byte[]{1, 2, 3});
        when(uploader.upload(any(InputStream.class), any(Map.class))).thenReturn(providerResponse(intent,
                "image", "upload", "png", 3L, secureUrl(intent, "image", "upload", "png")));

        MediaUploadResult result = storage.upload(intent, media);

        var options = uploadOptions();
        assertThat(options).containsEntry("asset_folder", "product-media/images")
                .containsEntry("public_id", intent.publicId())
                .containsEntry("resource_type", "image")
                .containsEntry("overwrite", false)
                .doesNotContainKey("folder");
        assertThat(result.publicId()).isEqualTo(intent.publicId());
        verify(uploader).upload(any(InputStream.class), eq(options));
    }

    @Test
    void qaLiveGlbUsesModelsAssetFolderWithoutChangingGeneratedPublicId() throws Exception {
        storage = new CloudinaryMediaStorageService(cloudinary, new MediaStorageProperties(),
                CloudinaryMediaStorageService.FolderPolicy.QA_DYNAMIC_PRODUCT_MEDIA);
        MediaUploadIntent intent = intent(MediaKind.MODEL_3D);
        ValidatedMedia media = validated(MediaKind.MODEL_3D, "glb", new byte[12]);
        when(uploader.upload(any(InputStream.class), any(Map.class))).thenReturn(providerResponse(intent,
                "raw", "upload", "glb", 12L, secureUrl(intent, "raw", "upload", "glb")));

        MediaUploadResult result = storage.upload(intent, media);

        var options = uploadOptions();
        assertThat(options).containsEntry("asset_folder", "product-media/models")
                .containsEntry("public_id", intent.publicId())
                .containsEntry("resource_type", "raw")
                .containsEntry("overwrite", false)
                .doesNotContainKey("folder");
        assertThat(result.publicId()).isEqualTo(intent.publicId());
        verify(uploader).upload(any(InputStream.class), eq(options));
    }

    @Test
    void rejectsMismatchedCloudinaryIdentity() throws Exception {
        MediaUploadIntent intent = intent(MediaKind.IMAGE);
        ValidatedMedia media = validated(MediaKind.IMAGE, "png", new byte[]{1, 2, 3});
        Map<String, Object> response = providerResponse(intent, "raw", "private", "png", 3L,
                "https://res.cloudinary.com/test-cloud/raw/private/v" + VERSION + "/unexpected.png");

        assertThatThrownBy(() -> uploadWith(intent, media, response))
                .isInstanceOf(MediaStorageException.class);
    }

    @Test
    void rejectsUnexpectedPublicIdAndCloudIdentity() throws Exception {
        MediaUploadIntent intent = intent(MediaKind.IMAGE);
        ValidatedMedia media = validated(MediaKind.IMAGE, "png", new byte[]{1, 2, 3});
        Map<String, Object> wrongPublicId = providerResponse(intent, "image", "upload", "png", 3L,
                secureUrl(intent, "image", "upload", "png"));
        wrongPublicId.put("public_id", "client-controlled-id");
        Map<String, Object> wrongCloud = providerResponse(intent, "image", "upload", "png", 3L,
                "https://res.cloudinary.com/other-cloud/image/upload/v" + VERSION + "/" + intent.publicId() + ".png");

        assertThatThrownBy(() -> uploadWith(intent, media, wrongPublicId))
                .isInstanceOf(MediaStorageException.class);
        assertThatThrownBy(() -> uploadWith(intent, media, wrongCloud))
                .isInstanceOf(MediaStorageException.class);
    }

    @Test
    void rejectsExplicitReturnedCloudNameMismatch() throws Exception {
        MediaUploadIntent intent = intent(MediaKind.IMAGE);
        ValidatedMedia media = validated(MediaKind.IMAGE, "png", new byte[]{1, 2, 3});
        Map<String, Object> response = providerResponse(intent, "image", "upload", "png", 3L,
                secureUrl(intent, "image", "upload", "png"));
        response.put("cloud_name", "other-cloud");

        assertThatThrownBy(() -> uploadWith(intent, media, response))
                .isInstanceOf(MediaStorageException.class);
    }

    @Test
    void acceptsJpegWhenCloudinaryReportsJpgAndKeepsProviderFormat() throws Exception {
        MediaUploadIntent intent = intent(MediaKind.IMAGE);
        ValidatedMedia media = validated(MediaKind.IMAGE, "jpeg", new byte[]{1, 2, 3});
        Map<String, Object> response = providerResponse(intent, "image", "upload", "jpg", 3L,
                secureUrl(intent, "image", "upload", "jpg"));
        when(uploader.upload(any(InputStream.class), any(Map.class))).thenReturn(response);

        MediaUploadResult result = storage.upload(intent, media);

        assertThat(result.format()).isEqualTo("jpg");
    }

    @Test
    void rejectsMissingSecureUrlOrAssetId() throws Exception {
        MediaUploadIntent intent = intent(MediaKind.IMAGE);
        ValidatedMedia media = validated(MediaKind.IMAGE, "png", new byte[]{1, 2, 3});
        Map<String, Object> missingUrl = providerResponse(intent, "image", "upload", "png", 3L, null);
        Map<String, Object> missingAssetId = providerResponse(intent, "image", "upload", "png", 3L,
                secureUrl(intent, "image", "upload", "png"));
        missingAssetId.remove("asset_id");

        assertThatThrownBy(() -> uploadWith(intent, media, missingUrl))
                .isInstanceOf(MediaStorageException.class);
        assertThatThrownBy(() -> uploadWith(intent, media, missingAssetId))
                .isInstanceOf(MediaStorageException.class);
    }

    @Test
    void rejectsMissingVersionFormatOrMismatchedByteCount() throws Exception {
        MediaUploadIntent intent = intent(MediaKind.IMAGE);
        ValidatedMedia media = validated(MediaKind.IMAGE, "png", new byte[]{1, 2, 3});
        Map<String, Object> missingVersion = providerResponse(intent, "image", "upload", "png", 3L,
                secureUrl(intent, "image", "upload", "png"));
        missingVersion.remove("version");
        Map<String, Object> wrongFormat = providerResponse(intent, "image", "upload", "webp", 3L,
                secureUrl(intent, "image", "upload", "webp"));
        Map<String, Object> wrongBytes = providerResponse(intent, "image", "upload", "png", 4L,
                secureUrl(intent, "image", "upload", "png"));

        assertThatThrownBy(() -> uploadWith(intent, media, missingVersion))
                .isInstanceOf(MediaStorageException.class);
        assertThatThrownBy(() -> uploadWith(intent, media, wrongFormat))
                .isInstanceOf(MediaStorageException.class);
        assertThatThrownBy(() -> uploadWith(intent, media, wrongBytes))
                .isInstanceOf(MediaStorageException.class);
    }

    @Test
    void deletesWithExactStoredResourceAndDeliveryTypes() throws Exception {
        MediaAssetIdentity identity = identity(MediaKind.MODEL_3D, "raw", "private");
        when(uploader.destroy(eq(identity.publicId()), any(Map.class)))
                .thenReturn(ObjectUtils.asMap("result", "ok"));

        assertThat(storage.delete(identity)).isEqualTo(DeleteResult.DELETED);

        var options = deleteOptions();
        assertThat(options).containsEntry("resource_type", "raw")
                .containsEntry("type", "private")
                .containsEntry("timeout", 60_000);
        verify(uploader).destroy(identity.publicId(), options);
    }

    @Test
    void deletesAuthenticatedThreeMfUsingItsStoredIdentity() throws Exception {
        MediaAssetIdentity identity = identity(MediaKind.PRINT_3MF, "raw", "authenticated");
        when(uploader.destroy(eq(identity.publicId()), any(Map.class)))
                .thenReturn(ObjectUtils.asMap("result", "ok"));

        assertThat(storage.delete(identity)).isEqualTo(DeleteResult.DELETED);

        assertThat(deleteOptions()).containsEntry("resource_type", "raw")
                .containsEntry("type", "authenticated");
    }

    @Test
    void deletesByGeneratedPublicIdWhenProviderAssetIdIsUnavailable() throws Exception {
        String publicId = generatedPublicId(MediaKind.MODEL_3D);
        MediaAssetIdentity identity = new MediaAssetIdentity(MediaProvider.CLOUDINARY, CLOUD_NAME, null,
                publicId, MediaKind.MODEL_3D, "raw", "upload");
        when(uploader.destroy(eq(publicId), any(Map.class))).thenReturn(ObjectUtils.asMap("result", "ok"));

        assertThat(storage.delete(identity)).isEqualTo(DeleteResult.DELETED);

        assertThat(deleteOptions()).containsEntry("resource_type", "raw").containsEntry("type", "upload");
        verify(uploader).destroy(eq(publicId), any(Map.class));
    }

    @Test
    void treatsProviderNotFoundAsIdempotentDelete() throws Exception {
        MediaAssetIdentity identity = identity(MediaKind.IMAGE, "image", "upload");
        when(uploader.destroy(eq(identity.publicId()), any(Map.class)))
                .thenReturn(ObjectUtils.asMap("result", "not found"));

        assertThat(storage.delete(identity)).isEqualTo(DeleteResult.NOT_FOUND);
    }

    @Test
    void refusesDeletionForAnotherCloudOrProvider() throws Exception {
        MediaAssetIdentity wrongCloud = new MediaAssetIdentity(MediaProvider.CLOUDINARY, "other-cloud", "asset-1",
                generatedPublicId(MediaKind.IMAGE), MediaKind.IMAGE, "image", "upload");

        assertThatThrownBy(() -> storage.delete(wrongCloud)).isInstanceOf(MediaStorageException.class);
        verifyNoInteractions(uploader);
    }

    @Test
    void refusesDeletionForPublicIdsOutsideTheServerGeneratedNamespace() {
        MediaAssetIdentity clientNamedIdentity = new MediaAssetIdentity(MediaProvider.CLOUDINARY, CLOUD_NAME,
                "cloud-asset-identity-123", "client-supplied-name", MediaKind.IMAGE, "image", "upload");

        assertThatThrownBy(() -> storage.delete(clientNamedIdentity)).isInstanceOf(MediaStorageException.class);
        verifyNoInteractions(uploader);
    }

    @Test
    void refusesDeletionForUnsupportedResourceOrDeliveryTypes() {
        MediaAssetIdentity wrongResource = new MediaAssetIdentity(MediaProvider.CLOUDINARY, CLOUD_NAME, null,
                generatedPublicId(MediaKind.IMAGE), MediaKind.IMAGE, "raw", "upload");
        MediaAssetIdentity wrongDelivery = new MediaAssetIdentity(MediaProvider.CLOUDINARY, CLOUD_NAME, null,
                generatedPublicId(MediaKind.IMAGE), MediaKind.IMAGE, "image", "arbitrary");

        assertThatThrownBy(() -> storage.delete(wrongResource)).isInstanceOf(MediaStorageException.class);
        assertThatThrownBy(() -> storage.delete(wrongDelivery)).isInstanceOf(MediaStorageException.class);
        verifyNoInteractions(uploader);
    }

    @Test
    void classifiesMultipartInputStreamAcquisitionFailureAsInputFailure() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(3L);
        when(file.getInputStream()).thenThrow(new IOException("local-path-and-secret"));
        ValidatedMedia media = new ValidatedMedia(file, MediaKind.IMAGE, "png", 3L, null, null);

        assertThatThrownBy(() -> storage.upload(intent(MediaKind.IMAGE), media))
                .isInstanceOfSatisfying(MediaStorageException.class, exception -> {
                    assertThat(exception.getReason()).isEqualTo(MediaStorageException.Reason.INPUT_STREAM_FAILURE);
                    assertThat(exception).hasMessageNotContaining("local-path-and-secret");
                });
        verifyNoInteractions(uploader);
    }

    @Test
    void classifiesMultipartInputStreamReadFailureAsInputFailure() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(3L);
        when(file.getInputStream()).thenReturn(new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("local filesystem details");
            }
        });
        ValidatedMedia media = new ValidatedMedia(file, MediaKind.IMAGE, "png", 3L, null, null);
        when(uploader.upload(any(InputStream.class), any(Map.class))).thenAnswer(invocation -> {
            ((InputStream) invocation.getArgument(0)).read();
            return Map.of();
        });

        assertThatThrownBy(() -> storage.upload(intent(MediaKind.IMAGE), media))
                .isInstanceOfSatisfying(MediaStorageException.class, exception ->
                        assertThat(exception.getReason()).isEqualTo(MediaStorageException.Reason.INPUT_STREAM_FAILURE));
    }

    @Test
    void classifiesCloudinaryIoFailureAsProviderUnavailableWithoutLeakingDetails() throws Exception {
        MediaUploadIntent intent = intent(MediaKind.IMAGE);
        ValidatedMedia media = validated(MediaKind.IMAGE, "png", new byte[]{1, 2, 3});
        when(uploader.upload(any(InputStream.class), any(Map.class)))
                .thenThrow(new IOException("api_secret=test-secret provider response payload"));

        assertThatThrownBy(() -> storage.upload(intent, media))
                .isInstanceOfSatisfying(MediaStorageException.class, exception -> {
                    assertThat(exception.getReason()).isEqualTo(MediaStorageException.Reason.PROVIDER_UNAVAILABLE);
                    assertThat(exception).hasMessageNotContaining("test-secret")
                            .hasMessageNotContaining("provider response payload");
                });
    }

    @Test
    void sanitizesProviderFailures() throws Exception {
        MediaUploadIntent intent = intent(MediaKind.IMAGE);
        ValidatedMedia media = validated(MediaKind.IMAGE, "png", new byte[]{1, 2, 3});
        when(uploader.upload(any(InputStream.class), any(Map.class)))
                .thenThrow(new java.io.IOException("api_secret=test-secret raw provider response"));

        assertThatThrownBy(() -> storage.upload(intent, media))
                .isInstanceOf(MediaStorageException.class)
                .extracting(exception -> ((MediaStorageException) exception).getReason())
                .isEqualTo(MediaStorageException.Reason.PROVIDER_UNAVAILABLE);
    }

    private MediaUploadIntent intent(MediaKind kind) {
        return MediaUploadIntent.create(42L, UUID.fromString("11111111-1111-4111-8111-111111111111"),
                UUID.fromString("22222222-2222-4222-8222-222222222222"), kind);
    }

    private ValidatedMedia validated(MediaKind kind, String format, byte[] bytes) {
        return new ValidatedMedia(new MockMultipartFile("file", "client-name." + format,
                "application/octet-stream", bytes), kind, format, bytes.length, null, null);
    }

    private Map<String, Object> providerResponse(MediaUploadIntent intent, String resourceType, String deliveryType,
                                                 String format, long bytes, String secureUrl) {
        Map<String, Object> response = new HashMap<>();
        response.put("asset_id", "cloud-asset-identity-123");
        response.put("cloud_name", CLOUD_NAME);
        response.put("public_id", intent.publicId());
        response.put("resource_type", resourceType);
        response.put("type", deliveryType);
        response.put("format", format);
        response.put("bytes", bytes);
        response.put("version", VERSION);
        if (secureUrl != null) {
            response.put("secure_url", secureUrl);
        }
        return response;
    }

    private String secureUrl(MediaUploadIntent intent, String resourceType, String deliveryType, String format) {
        String suffix = intent.mediaKind() == MediaKind.IMAGE ? "." + format : "";
        return "https://res.cloudinary.com/" + CLOUD_NAME + "/" + resourceType + "/" + deliveryType
                + "/v" + VERSION + "/" + intent.publicId() + suffix;
    }

    private MediaUploadResult uploadWith(MediaUploadIntent intent, ValidatedMedia media, Map<String, Object> response)
            throws Exception {
        when(uploader.upload(any(InputStream.class), any(Map.class))).thenReturn(response);
        return storage.upload(intent, media);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> uploadOptions() throws Exception {
        var captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(uploader, org.mockito.Mockito.atLeastOnce()).upload(any(InputStream.class), captor.capture());
        return captor.getValue();
    }

    private Object uploadedObject() throws Exception {
        var captor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(uploader, org.mockito.Mockito.atLeastOnce()).upload(captor.capture(), any(Map.class));
        return captor.getValue();
    }

    private MediaAssetIdentity identity(MediaKind kind, String resourceType, String deliveryType) {
        String publicId = generatedPublicId(kind);
        return new MediaAssetIdentity(MediaProvider.CLOUDINARY, CLOUD_NAME, "cloud-asset-identity-123",
                publicId, kind, resourceType, deliveryType);
    }

    private String generatedPublicId(MediaKind kind) {
        String prefix = kind == MediaKind.MODEL_3D ? "models"
                : kind == MediaKind.PRINT_3MF ? "print-files" : "images";
        String suffix = kind == MediaKind.MODEL_3D ? ".glb" : kind == MediaKind.PRINT_3MF ? ".3mf" : "";
        return "product-media/" + prefix + "/11111111-1111-4111-8111-111111111111/42" + suffix;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deleteOptions() throws Exception {
        var captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(uploader).destroy(any(String.class), captor.capture());
        return captor.getValue();
    }
}
