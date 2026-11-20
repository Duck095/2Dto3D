package com.exe.morphia3d.service;

import com.exe.morphia3d.entity.MediaAsset;
import com.exe.morphia3d.enums.MediaAssetState;
import com.exe.morphia3d.enums.MediaKind;
import com.exe.morphia3d.enums.MediaProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;

/** Returns stored delivery URLs only when the linked media row matches its expected product identity. */
@Component
public class MediaUrlResolver {

    private static final String DELIVERY_HOST = "res.cloudinary.com";

    private final String cloudName;

    public MediaUrlResolver(@Value("${cloudinary.cloud-name}") String cloudName) {
        this.cloudName = cloudName;
    }

    public String resolve(MediaAsset asset, MediaKind expectedKind) {
        if (asset == null || expectedKind == null || cloudName == null || cloudName.isBlank()
                || asset.getId() == null || asset.getId() <= 0
                || asset.getOperationId() == null
                || asset.getProvider() != MediaProvider.CLOUDINARY
                || !cloudName.equals(asset.getCloudName())
                || asset.getMediaKind() != expectedKind
                || asset.getState() != MediaAssetState.ATTACHED
                || asset.getProviderAssetId() == null || asset.getProviderAssetId().isBlank()
                || asset.getByteCount() == null || asset.getByteCount() <= 0
                || asset.getProviderVersion() == null || asset.getProviderVersion() <= 0) {
            return null;
        }

        String expectedResource = expectedKind == MediaKind.IMAGE ? "image" : "raw";
        if (!expectedResource.equals(asset.getResourceType()) || !"upload".equals(asset.getDeliveryType())) {
            return null;
        }

        String format = asset.getFormat();
        String normalizedFormat = normalizeFormat(format);
        if (expectedKind == MediaKind.IMAGE) {
            if (!"jpeg".equals(normalizedFormat) && !"png".equals(normalizedFormat)
                    && !"webp".equals(normalizedFormat)) {
                return null;
            }
        } else if (!"glb".equals(normalizedFormat)) {
            return null;
        }

        String folder = expectedKind == MediaKind.IMAGE ? "images" : "models";
        String suffix = expectedKind == MediaKind.MODEL_3D ? ".glb" : "";
        String expectedPublicId = "product-media/" + folder + "/" + asset.getOperationId()
                + "/" + asset.getId() + suffix;
        if (!expectedPublicId.equals(asset.getPublicId())) {
            return null;
        }

        String url = asset.getSecureUrl();
        if (url == null || url.isBlank()) {
            return null;
        }
        String deliveryPath = "/" + cloudName + "/" + expectedResource + "/upload/v"
                + asset.getProviderVersion() + "/" + expectedPublicId
                + (expectedKind == MediaKind.IMAGE ? "." + format : "");
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !DELIVERY_HOST.equalsIgnoreCase(uri.getHost())
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !deliveryPath.equals(uri.getPath())) {
                return null;
            }
            return url;
        } catch (IllegalArgumentException invalidUrl) {
            return null;
        }
    }

    private String normalizeFormat(String format) {
        if (format == null) {
            return "";
        }
        String normalized = format.trim().toLowerCase(Locale.ROOT);
        return "jpg".equals(normalized) ? "jpeg" : normalized;
    }
}
