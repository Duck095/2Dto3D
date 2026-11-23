package com.exe.morphia3d.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "media.upload")
public class MediaUploadProperties {

    private long imageMaxBytes = 10L * 1024 * 1024;
    private int maxImages = 8;
    private long modelMaxBytes = 50L * 1024 * 1024;
    private long print3mfMaxBytes = 50L * 1024 * 1024;
    private long print3mfMaxExpandedBytes = 250L * 1024 * 1024;
    private int print3mfMaxEntries = 4096;
    private long decodedPixelMax = 40_000_000L;
    private int snapshotMaxConcurrent = 1;
    private String snapshotTempDirectory = System.getProperty("java.io.tmpdir", ".");

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

    public long getPrint3mfMaxBytes() {
        return print3mfMaxBytes;
    }

    public void setPrint3mfMaxBytes(long print3mfMaxBytes) {
        this.print3mfMaxBytes = print3mfMaxBytes;
    }

    public long getPrint3mfMaxExpandedBytes() {
        return print3mfMaxExpandedBytes;
    }

    public void setPrint3mfMaxExpandedBytes(long print3mfMaxExpandedBytes) {
        this.print3mfMaxExpandedBytes = print3mfMaxExpandedBytes;
    }

    public int getPrint3mfMaxEntries() {
        return print3mfMaxEntries;
    }

    public void setPrint3mfMaxEntries(int print3mfMaxEntries) {
        this.print3mfMaxEntries = print3mfMaxEntries;
    }

    public long getDecodedPixelMax() {
        return decodedPixelMax;
    }

    public void setDecodedPixelMax(long decodedPixelMax) {
        this.decodedPixelMax = decodedPixelMax;
    }

    public int getSnapshotMaxConcurrent() {
        return snapshotMaxConcurrent;
    }

    public void setSnapshotMaxConcurrent(int snapshotMaxConcurrent) {
        if (snapshotMaxConcurrent < 1) {
            throw new IllegalArgumentException("Snapshot upload concurrency must be positive");
        }
        this.snapshotMaxConcurrent = snapshotMaxConcurrent;
    }

    public String getSnapshotTempDirectory() {
        return snapshotTempDirectory;
    }

    public void setSnapshotTempDirectory(String snapshotTempDirectory) {
        if (snapshotTempDirectory == null || snapshotTempDirectory.isBlank()) {
            throw new IllegalArgumentException("Snapshot temporary directory is required");
        }
        this.snapshotTempDirectory = snapshotTempDirectory;
    }
}
