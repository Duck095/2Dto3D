package com.exe.morphia3d.config;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.exe.morphia3d.service.MediaStorageProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CloudinaryConfig {

    @Value("${cloudinary.cloud-name}")
    private String cloudName;

    @Value("${cloudinary.api-key}")
    private String apiKey;

    @Value("${cloudinary.api-secret}")
    private String apiSecret;

    @Bean
    public Cloudinary cloudinary(MediaStorageProperties mediaStorageProperties) {
        mediaStorageProperties.validate();
        return new Cloudinary(ObjectUtils.asMap(
                "cloud_name", cloudName,
                "api_key", apiKey,
                "api_secret", apiSecret,
                "secure", true,
                // SDK configuration timeout is seconds; adapter calls also set per-request millisecond bounds.
                "timeout", Math.max(1, mediaStorageProperties.getRequestTimeoutMillis() / 1000)
        ));
    }
}
