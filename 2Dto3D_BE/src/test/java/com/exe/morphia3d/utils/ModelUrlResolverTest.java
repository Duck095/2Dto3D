package com.exe.morphia3d.utils;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class ModelUrlResolverTest {

    @Test
    void resolvesRelativeGlbPathToSecureCloudinaryRawUploadUrl() {
        ModelUrlResolver resolver = new ModelUrlResolver();
        ReflectionTestUtils.setField(resolver, "baseUrl", "https://res.cloudinary.com/zsxcxwta/image/upload");

        assertThat(resolver.resolve("product-media/models/synthetic.glb"))
                .isEqualTo("https://res.cloudinary.com/zsxcxwta/raw/upload/product-media/models/synthetic.glb");
    }

    @Test
    void rejectsLegacyFakeCloudinaryEndpoint() {
        ModelUrlResolver resolver = new ModelUrlResolver();
        ReflectionTestUtils.setField(resolver, "baseUrl", "https://res.cloudinary.com/zsxcxwta/image/upload");

        assertThat(resolver.resolve("http://localhost.invalid/cloudinary/models/legacy.glb")).isNull();
    }

}
