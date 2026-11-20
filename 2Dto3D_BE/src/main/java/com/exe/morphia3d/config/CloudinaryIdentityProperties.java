package com.exe.morphia3d.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Shared configured Cloudinary identity used by provider setup and lifecycle validation. */
@Component
@ConfigurationProperties(prefix = "cloudinary")
public class CloudinaryIdentityProperties {
    private String cloudName;

    public String getCloudName() {
        return cloudName;
    }

    public void setCloudName(String cloudName) {
        this.cloudName = cloudName;
    }
}
