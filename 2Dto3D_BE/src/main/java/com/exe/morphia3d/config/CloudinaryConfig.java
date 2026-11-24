package com.exe.morphia3d.config;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.exe.morphia3d.service.MediaStorageProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CloudinaryConfig {

    @Bean
    @Conditional(QaMediaModeCondition.NonQa.class)
    public Cloudinary cloudinary(CloudinaryIdentityProperties identityProperties,
                                 MediaStorageProperties mediaStorageProperties,
                                 @Value("${cloudinary.api-key}") String apiKey,
                                 @Value("${cloudinary.api-secret}") String apiSecret) {
        mediaStorageProperties.validate();
        return createClient(identityProperties.getCloudName(), apiKey, apiSecret, mediaStorageProperties, null, 0);
    }

    @Bean("cloudinary")
    @Conditional(QaMediaModeCondition.QaLive.class)
    public Cloudinary qaCloudinary(CloudinaryIdentityProperties identityProperties,
                                  MediaStorageProperties mediaStorageProperties,
                                  QaMediaActivation activation) {
        if (!activation.cloudName().equals(identityProperties.getCloudName())) {
            throw new IllegalStateException("QA Cloudinary identity configuration is inconsistent");
        }
        return createClient(activation.cloudName(), activation.apiKey(), activation.apiSecret(),
                mediaStorageProperties, activation.proxyHost(), activation.proxyPort());
    }

    private Cloudinary createClient(String cloudName, String apiKey, String apiSecret,
                                    MediaStorageProperties mediaStorageProperties, String proxyHost, int proxyPort) {
        mediaStorageProperties.validate();
        requireConfigured(cloudName, "Cloudinary cloud name is required for real media storage");
        requireConfigured(apiKey, "Cloudinary API key is required for real media storage");
        requireConfigured(apiSecret, "Cloudinary API secret is required for real media storage");
        java.util.Map<String, Object> configuration = ObjectUtils.asMap("cloud_name", cloudName, "api_key", apiKey,
                "api_secret", apiSecret, "secure", true,
                // SDK configuration timeout is seconds; adapter calls also set per-request millisecond bounds.
                "timeout", Math.max(1, mediaStorageProperties.getRequestTimeoutMillis() / 1000));
        if (proxyHost != null) {
            configuration.put("proxy_host", proxyHost);
            configuration.put("proxy_port", proxyPort);
        }
        return new Cloudinary(configuration);
    }

    private void requireConfigured(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(message);
        }
    }
}
