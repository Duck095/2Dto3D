package com.exe.morphia3d.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "media.lifecycle")
public class MediaLifecycleProperties {

    private long uploadLeaseMillis = 90_000;
    private long cleanupLeaseMillis = 90_000;
    private long safetyGraceMillis = 90_000;
    private long retryBaseDelayMillis = 30_000;
    private long retryMaxDelayMillis = 3_600_000;
    private int maxCleanupAttempts = 8;
    private int batchSize = 100;
    private long reconciliationIntervalMillis = 30_000;
    private long timeoutSafetyMarginMillis = 10_000;
    private int heartbeatPoolSize = 200;

    public void validate(int providerTimeoutMillis) {
        long minimumLease = Math.addExact((long) providerTimeoutMillis, timeoutSafetyMarginMillis);
        if (providerTimeoutMillis <= 0 || uploadLeaseMillis < minimumLease
                || cleanupLeaseMillis < minimumLease || safetyGraceMillis < minimumLease
                || retryBaseDelayMillis <= 0 || retryMaxDelayMillis < retryBaseDelayMillis
                || maxCleanupAttempts <= 0 || batchSize <= 0 || reconciliationIntervalMillis <= 0
                || timeoutSafetyMarginMillis <= 0 || heartbeatPoolSize <= 0) {
            throw new IllegalStateException("Media lifecycle lease, retry, and reconciliation settings are invalid");
        }
    }

    public long getUploadLeaseMillis() { return uploadLeaseMillis; }
    public void setUploadLeaseMillis(long uploadLeaseMillis) { this.uploadLeaseMillis = uploadLeaseMillis; }
    public long getCleanupLeaseMillis() { return cleanupLeaseMillis; }
    public void setCleanupLeaseMillis(long cleanupLeaseMillis) { this.cleanupLeaseMillis = cleanupLeaseMillis; }
    public long getSafetyGraceMillis() { return safetyGraceMillis; }
    public void setSafetyGraceMillis(long safetyGraceMillis) { this.safetyGraceMillis = safetyGraceMillis; }
    public long getRetryBaseDelayMillis() { return retryBaseDelayMillis; }
    public void setRetryBaseDelayMillis(long retryBaseDelayMillis) { this.retryBaseDelayMillis = retryBaseDelayMillis; }
    public long getRetryMaxDelayMillis() { return retryMaxDelayMillis; }
    public void setRetryMaxDelayMillis(long retryMaxDelayMillis) { this.retryMaxDelayMillis = retryMaxDelayMillis; }
    public int getMaxCleanupAttempts() { return maxCleanupAttempts; }
    public void setMaxCleanupAttempts(int maxCleanupAttempts) { this.maxCleanupAttempts = maxCleanupAttempts; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public long getReconciliationIntervalMillis() { return reconciliationIntervalMillis; }
    public void setReconciliationIntervalMillis(long reconciliationIntervalMillis) {
        this.reconciliationIntervalMillis = reconciliationIntervalMillis;
    }
    public long getTimeoutSafetyMarginMillis() { return timeoutSafetyMarginMillis; }
    public void setTimeoutSafetyMarginMillis(long timeoutSafetyMarginMillis) {
        this.timeoutSafetyMarginMillis = timeoutSafetyMarginMillis;
    }
    public int getHeartbeatPoolSize() { return heartbeatPoolSize; }
    public void setHeartbeatPoolSize(int heartbeatPoolSize) { this.heartbeatPoolSize = heartbeatPoolSize; }
}
