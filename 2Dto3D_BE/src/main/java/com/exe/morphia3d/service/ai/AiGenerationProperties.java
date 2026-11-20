package com.exe.morphia3d.service.ai;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.ai-generation")
public class AiGenerationProperties {
    private boolean enabled;
    private boolean workerEnabled;
    @Min(1)
    private int maxPromptChars = 255;
    @Positive
    private long maxImageBytes = 10L * 1024 * 1024;
    @Positive
    private int requestsPerMinutePerOwner = 3;
    @Positive
    private long workerIntervalMillis = 1_000;
    @Positive
    private long providerTimeoutMillis = 30_000;
    @Min(1)
    private int maxAttempts = 2;
    @Min(0)
    private long retryBackoffMillis = 1_000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isWorkerEnabled() { return workerEnabled; }
    public void setWorkerEnabled(boolean workerEnabled) { this.workerEnabled = workerEnabled; }
    public int getMaxPromptChars() { return maxPromptChars; }
    public void setMaxPromptChars(int maxPromptChars) { this.maxPromptChars = maxPromptChars; }
    public long getMaxImageBytes() { return maxImageBytes; }
    public void setMaxImageBytes(long maxImageBytes) { this.maxImageBytes = maxImageBytes; }
    public int getRequestsPerMinutePerOwner() { return requestsPerMinutePerOwner; }
    public void setRequestsPerMinutePerOwner(int requestsPerMinutePerOwner) {
        this.requestsPerMinutePerOwner = requestsPerMinutePerOwner;
    }
    public long getWorkerIntervalMillis() { return workerIntervalMillis; }
    public void setWorkerIntervalMillis(long workerIntervalMillis) { this.workerIntervalMillis = workerIntervalMillis; }
    public long getProviderTimeoutMillis() { return providerTimeoutMillis; }
    public void setProviderTimeoutMillis(long providerTimeoutMillis) { this.providerTimeoutMillis = providerTimeoutMillis; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public long getRetryBackoffMillis() { return retryBackoffMillis; }
    public void setRetryBackoffMillis(long retryBackoffMillis) { this.retryBackoffMillis = retryBackoffMillis; }
}
