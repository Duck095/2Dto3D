package com.exe.morphia3d.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** QA-only Cloudinary activation inputs. Secrets are intentionally not exposed through toString(). */
@ConfigurationProperties(prefix = "app.qa-media")
public class QaMediaActivationProperties {
    private boolean liveEnabled;
    private boolean liveApproved;
    private String cloudName;
    private String apiKey;
    private String apiSecret;
    private String proxyHost;
    private int proxyPort;

    public boolean isLiveEnabled() { return liveEnabled; }
    public void setLiveEnabled(boolean liveEnabled) { this.liveEnabled = liveEnabled; }
    public boolean isLiveApproved() { return liveApproved; }
    public void setLiveApproved(boolean liveApproved) { this.liveApproved = liveApproved; }
    public String getCloudName() { return cloudName; }
    public void setCloudName(String cloudName) { this.cloudName = cloudName; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getApiSecret() { return apiSecret; }
    public void setApiSecret(String apiSecret) { this.apiSecret = apiSecret; }
    public String getProxyHost() { return proxyHost; }
    public void setProxyHost(String proxyHost) { this.proxyHost = proxyHost; }
    public int getProxyPort() { return proxyPort; }
    public void setProxyPort(int proxyPort) { this.proxyPort = proxyPort; }
}
