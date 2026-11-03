package com.exe.morphia3d.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "media.upload")
public class MediaUploadProperties {

    private long imageMaxBytes = 10L * 1024 * 1024;
    private int maxImages = 8;
    private long modelMaxBytes = 50L * 1024 * 1024;
    private long decodedPixelMax = 40_000_000L;

    public long getImageMaxBytes() {
        return imageMaxBytes;
    }

    public void setImageMaxBytes(long imageMaxBytes) {
        this.imageMaxBytes = imageMaxBytes;
    }

    public int getMaxImages() {
        return maxImages;
    }

    public void setMaxImages(int maxImages) {
        this.maxImages = maxImages;
    }

    public long getModelMaxBytes() {
        return modelMaxBytes;
    }

    public void setModelMaxBytes(long modelMaxBytes) {
        this.modelMaxBytes = modelMaxBytes;
    }

    public long getDecodedPixelMax() {
        return decodedPixelMax;
    }

    public void setDecodedPixelMax(long decodedPixelMax) {
        this.decodedPixelMax = decodedPixelMax;
    }
}
