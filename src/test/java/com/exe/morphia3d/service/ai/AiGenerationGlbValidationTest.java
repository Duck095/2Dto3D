package com.exe.morphia3d.service.ai;

import com.exe.morphia3d.service.MediaUploadProperties;
import com.exe.morphia3d.service.MediaValidationException;
import com.exe.morphia3d.service.MediaValidationService;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiGenerationGlbValidationTest {
    @Test
    void acceptsOnlyAConsistentGlbV2ByteArrayWithinConfiguredLimit() {
        MediaUploadProperties properties = new MediaUploadProperties();
        properties.setModelMaxBytes(12);
        MediaValidationService validation = new MediaValidationService(properties);
        byte[] bytes = validGlbHeader(12);

        ValidatedGlb validated = validation.validateGlb(bytes, "generated.glb", "model/gltf-binary");

        assertThat(validated.byteCount()).isEqualTo(12);
        assertThat(validated.bytes()).containsExactly(bytes);
    }

    @Test
    void rejectsInvalidSignatureVersionLengthMimeAndOversizedOutput() {
        MediaUploadProperties properties = new MediaUploadProperties();
        properties.setModelMaxBytes(12);
        MediaValidationService validation = new MediaValidationService(properties);

        assertInvalid(validation, validGlbHeader(11), "model.glb", "model/gltf-binary", true);
        assertInvalid(validation, validGlbHeader(12), "model.glb", "image/png", false);
        assertInvalid(validation, validGlbHeader(12), "model.obj", "model/gltf-binary", false);
        assertInvalid(validation, Arrays.copyOf(validGlbHeader(13), 13), "model.glb", "model/gltf-binary", false);
        assertInvalid(validation, new byte[12], "model.glb", "model/gltf-binary", false);
    }

    private void assertInvalid(MediaValidationService validation, byte[] bytes, String filename,
                               String contentType, boolean invalidVersion) {
        if (invalidVersion) {
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 4);
        }
        assertThatThrownBy(() -> validation.validateGlb(bytes, filename, contentType))
                .isInstanceOf(MediaValidationException.class);
    }

    private byte[] validGlbHeader(int declaredLength) {
        return ByteBuffer.allocate(12)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put((byte) 'g').put((byte) 'l').put((byte) 'T').put((byte) 'F')
                .putInt(2)
                .putInt(declaredLength)
                .array();
    }
}
