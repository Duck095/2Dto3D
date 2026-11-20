package com.exe.morphia3d.service;

import com.exe.morphia3d.entity.MediaAsset;
import com.exe.morphia3d.enums.MediaAssetState;
import com.exe.morphia3d.enums.MediaKind;
import com.exe.morphia3d.enums.MediaProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class MediaUrlResolverTest {
    private static final String CLOUD = "test-cloud";
    private static final UUID OPERATION_ID = UUID.fromString("08e629f0-7489-4a58-9b12-01e71eead2cd");

    private MediaUrlResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new MediaUrlResolver(CLOUD);
    }

    @Test
    void resolvesTrustedAttachedImageAndGlbUrls() {
        MediaAsset image = asset(12L, MediaKind.IMAGE, "png", "image", "/test-cloud/image/upload/v7/"
                + "product-media/images/" + OPERATION_ID + "/12.png");
        MediaAsset model = asset(13L, MediaKind.MODEL_3D, "glb", "raw", "/test-cloud/raw/upload/v7/"
                + "product-media/models/" + OPERATION_ID + "/13.glb");

        assertThat(resolver.resolve(image, MediaKind.IMAGE)).isEqualTo(image.getSecureUrl());
        assertThat(resolver.resolve(model, MediaKind.MODEL_3D)).isEqualTo(model.getSecureUrl());
    }

    @Test
    void rejectsInvalidProviderCloudKindStateAndDeliveryIdentity() {
        MediaAsset validImage = asset(12L, MediaKind.IMAGE, "png", "image", "/test-cloud/image/upload/v7/"
                + "product-media/images/" + OPERATION_ID + "/12.png");

        assertRejected(validImage, asset -> asset.setProvider(null));
        assertRejected(validImage, asset -> asset.setCloudName("other-cloud"));
        assertRejected(validImage, asset -> asset.setMediaKind(MediaKind.MODEL_3D));
        assertRejected(validImage, asset -> asset.setResourceType("raw"));
        assertRejected(validImage, asset -> asset.setDeliveryType("private"));
        assertRejected(validImage, asset -> asset.setState(MediaAssetState.READY));
    }

    @Test
    void rejectsInvalidFormatPublicIdAndSecureUrl() {
        MediaAsset validImage = asset(12L, MediaKind.IMAGE, "png", "image", "/test-cloud/image/upload/v7/"
                + "product-media/images/" + OPERATION_ID + "/12.png");

        assertRejected(validImage, asset -> asset.setFormat("gif"));
        assertRejected(validImage, asset -> asset.setPublicId("client/path.png"));
        assertRejected(validImage, asset -> asset.setPublicId(
                "product-media/images/" + OPERATION_ID + "/999"));
        assertRejected(validImage, asset -> asset.setSecureUrl(
                "https://attacker.example/test-cloud/image/upload/v7/product-media/images/" + OPERATION_ID + "/12.png"));
        assertRejected(validImage, asset -> asset.setSecureUrl(
                "http://res.cloudinary.com/test-cloud/image/upload/v7/product-media/images/" + OPERATION_ID + "/12.png"));
        assertRejected(validImage, asset -> asset.setSecureUrl(
                "https://res.cloudinary.com/other-cloud/image/upload/v7/product-media/images/" + OPERATION_ID + "/12.png"));
    }

    @Test
    void rejectsMissingProviderAssetIdentityOrVersion() {
        MediaAsset validImage = asset(12L, MediaKind.IMAGE, "png", "image", "/test-cloud/image/upload/v7/"
                + "product-media/images/" + OPERATION_ID + "/12.png");

        assertThat(resolver.resolve(null, MediaKind.IMAGE)).isNull();
        assertRejected(validImage, asset -> asset.setProviderAssetId(null));
        assertRejected(validImage, asset -> asset.setProviderVersion(0L));
        assertRejected(validImage, asset -> asset.setByteCount(0L));
        assertRejected(validImage, asset -> asset.setSecureUrl(null));
        assertRejected(validImage, asset -> asset.setFormat(null));
    }

    private void assertRejected(MediaAsset valid, Consumer<MediaAsset> mutation) {
        MediaAsset candidate = asset(valid.getId(), valid.getMediaKind(), valid.getFormat(), valid.getResourceType(),
                valid.getSecureUrl().substring("https://res.cloudinary.com".length()));
        candidate.setProvider(valid.getProvider());
        candidate.setCloudName(valid.getCloudName());
        candidate.setDeliveryType(valid.getDeliveryType());
        candidate.setPublicId(valid.getPublicId());
        candidate.setProviderAssetId(valid.getProviderAssetId());
        candidate.setOperationId(valid.getOperationId());
        candidate.setProviderVersion(valid.getProviderVersion());
        candidate.setByteCount(valid.getByteCount());
        candidate.setState(valid.getState());
        mutation.accept(candidate);
        assertThat(resolver.resolve(candidate, MediaKind.IMAGE)).isNull();
    }

    private MediaAsset asset(Long id, MediaKind kind, String format, String resourceType, String urlPath) {
        String folder = kind == MediaKind.IMAGE ? "images" : "models";
        String suffix = kind == MediaKind.IMAGE ? "" : ".glb";
        String publicId = "product-media/" + folder + "/" + OPERATION_ID + "/" + id + suffix;
        return MediaAsset.builder().id(id).operationId(OPERATION_ID).mediaKind(kind)
                .provider(MediaProvider.CLOUDINARY).cloudName(CLOUD).providerAssetId("provider-asset-" + id)
                .publicId(publicId).resourceType(resourceType).deliveryType("upload").format(format)
                .byteCount(10L).providerVersion(7L).secureUrl("https://res.cloudinary.com" + urlPath)
                .state(MediaAssetState.ATTACHED).build();
    }
}
