package com.exe.morphia3d.feature;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class CloudinaryLocalPropertiesTest {

    @Test
    void localProfileRequiresRealCredentialsAndUsesSecureCloudinaryImageBase() throws Exception {
        Properties properties = new Properties();
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("application-local.properties")) {
            assertThat(input).as("application-local.properties resource").isNotNull();
            properties.load(input);
        }

        assertThat(properties.getProperty("cloudinary.cloud-name")).isEqualTo("${CLOUD_NAME}");
        assertThat(properties.getProperty("cloudinary.api-key")).isEqualTo("${API_KEY}");
        assertThat(properties.getProperty("cloudinary.api-secret")).isEqualTo("${API_SECRET}");
        assertThat(properties.getProperty("cloudinary.base-url"))
                .isEqualTo("https://res.cloudinary.com/${CLOUD_NAME}/image/upload")
                .doesNotContain("localhost.invalid");
        assertThat(properties.getProperty("media.storage.cloudinary-folder-policy"))
                .isEqualTo("local-product-media");
    }
}
