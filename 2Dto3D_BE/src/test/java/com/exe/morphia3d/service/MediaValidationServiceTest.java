package com.exe.morphia3d.service;

import com.exe.morphia3d.enums.MediaKind;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaValidationServiceTest {

    private final MediaUploadProperties properties = new MediaUploadProperties();
    private final MediaValidationService validator = new MediaValidationService(properties);

    @Test
    void acceptsValidJpegPngAndWebp() throws Exception {
        var jpeg = image("chair.jpg", "image/jpeg", "jpg");
        var png = image("chair.png", "image/png", "png");
        var webp = new MockMultipartFile("images", "chair.webp", "image/webp",
                Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEAAUAmJaQAA3AA/v89WAAAAA=="));

        List<ValidatedMedia> validated = validator.validateImages(List.of(jpeg, png, webp));

        assertThat(validated).hasSize(3);
        assertThat(validated).extracting(ValidatedMedia::mediaKind).containsOnly(MediaKind.IMAGE);
        assertThat(validated).extracting(ValidatedMedia::format).containsExactly("jpeg", "png", "webp");
        assertThat(validated).extracting(ValidatedMedia::byteCount).containsExactly((long) jpeg.getSize(), (long) png.getSize(), (long) webp.getSize());
        assertThat(validated).allSatisfy(media -> {
            assertThat(media.width()).isPositive();
            assertThat(media.height()).isPositive();
        });
    }

    @Test
    void rejectsImageNameMimeSignatureMismatch() throws Exception {
        var png = image("chair.png", "image/png", "png");

        assertThatThrownBy(() -> validator.validateImages(List.of(
                new MockMultipartFile("images", "chair.jpg", "image/png", png.getBytes()))))
                .isInstanceOf(MediaValidationException.class);
        assertThatThrownBy(() -> validator.validateImages(List.of(
                new MockMultipartFile("images", "chair.png", "image/jpeg", png.getBytes()))))
                .isInstanceOf(MediaValidationException.class);
        assertThatThrownBy(() -> validator.validateImages(List.of(
                new MockMultipartFile("images", "chair.png", "image/png", new byte[]{1, 2, 3, 4}))))
                .isInstanceOf(MediaValidationException.class);
    }

    @Test
    void rejectsEmptyAndOversizedImages() throws Exception {
        byte[] png = image("chair.png", "image/png", "png").getBytes();
        properties.setImageMaxBytes(png.length - 1L);

        assertThatThrownBy(() -> validator.validateImages(List.of(
                new MockMultipartFile("images", "empty.png", "image/png", new byte[0]))))
                .isInstanceOf(MediaValidationException.class);
        assertThatThrownBy(() -> validator.validateImages(List.of(
                new MockMultipartFile("images", "large.png", "image/png", png))))
                .isInstanceOf(MediaValidationException.class);
    }

    @Test
    void rejectsImageAboveDecodedPixelLimit() throws Exception {
        properties.setDecodedPixelMax(5);
        MockMultipartFile png = image("chair.png", "image/png", "png");

        assertThatThrownBy(() -> validator.validateImages(List.of(png)))
                .isInstanceOf(MediaValidationException.class);
    }

    @Test
    void rejectsMoreThanEightImages() throws Exception {
        properties.setMaxImages(2);
        var image = image("chair.png", "image/png", "png");

        assertThatThrownBy(() -> validator.validateImages(List.of(image, image, image)))
                .isInstanceOf(MediaValidationException.class);
    }

    @Test
    void rejectsMalformedImageDecode() {
        byte[] pngHeader = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 0};

        assertThatThrownBy(() -> validator.validateImages(List.of(
                new MockMultipartFile("images", "broken.png", "image/png", pngHeader))))
                .isInstanceOf(MediaValidationException.class);
    }

    @Test
    void acceptsValidGlbHeader() {
        MockMultipartFile glb = glb("chair.glb", "model/gltf-binary", "glTF", 2, 12);

        ValidatedMedia media = validator.validateGlb(glb);

        assertThat(media.mediaKind()).isEqualTo(MediaKind.MODEL_3D);
        assertThat(media.format()).isEqualTo("glb");
        assertThat(media.byteCount()).isEqualTo(12);
    }

    @Test
    void rejectsGlbWrongMagicVersionOrDeclaredLength() {
        assertRejectedGlb(glb("chair.glb", "model/gltf-binary", "nope", 2, 12));
        assertRejectedGlb(glb("chair.glb", "model/gltf-binary", "glTF", 1, 12));
        assertRejectedGlb(glb("chair.glb", "model/gltf-binary", "glTF", 2, 13));
    }

    @Test
    void rejectsGlbWhenStreamLengthDiffersFromReportedFileSize() {
        byte[] bytes = new byte[13];
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("glTF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        buffer.putInt(4, 2);
        buffer.putInt(8, 12);
        MockMultipartFile dishonestSize = new MockMultipartFile("model", "chair.glb", "model/gltf-binary", bytes) {
            @Override
            public long getSize() {
                return 12;
            }
        };

        assertRejectedGlb(dishonestSize);
    }

    @Test
    void rejectsNonGlbModel() {
        MockMultipartFile stl = new MockMultipartFile("model", "chair.stl", "model/stl", new byte[12]);
        MockMultipartFile wrongMime = glb("chair.glb", "model/stl", "glTF", 2, 12);

        assertThatThrownBy(() -> validator.validateGlb(stl))
                .isInstanceOf(MediaValidationException.class);
        assertThatThrownBy(() -> validator.validateGlb(wrongMime))
                .isInstanceOf(MediaValidationException.class);
    }

    @Test
    void rejectsGlbAboveConfiguredModelLimit() {
        properties.setModelMaxBytes(11);

        assertRejectedGlb(glb("chair.glb", "model/gltf-binary", "glTF", 2, 12));
    }

    @Test
    void validatesGlbThroughStreamWithoutCallingGetBytes() throws Exception {
        MockMultipartFile glb = new MockMultipartFile("model", "chair.glb", "model/gltf-binary",
                glb("chair.glb", "model/gltf-binary", "glTF", 2, 12).getBytes()) {
            @Override
            public byte[] getBytes() {
                throw new AssertionError("GLB validation must stream instead of buffering the file");
            }
        };

        assertThat(validator.validateGlb(glb).byteCount()).isEqualTo(12);
    }

    @Test
    void acceptsValidThreeMfArchiveAndReportsPrintMedia() throws Exception {
        byte[] archive = threeMf("3D/3dmodel.model", "<model/>".getBytes());
        var upload = threeMfUpload("chair.3mf", "model/3mf", archive);

        ValidatedMedia media = validator.validate3mf(upload);

        assertThat(media.mediaKind()).isEqualTo(MediaKind.PRINT_3MF);
        assertThat(media.format()).isEqualTo("3mf");
        assertThat(media.byteCount()).isEqualTo(archive.length);
    }

    @Test
    void rejectsThreeMfWithoutRequiredModelMember() throws Exception {
        byte[] archive = threeMf("metadata/thumbnail.png", new byte[]{1});

        assertThatThrownBy(() -> validator.validate3mf(threeMfUpload("chair.3mf", "application/octet-stream", archive)))
                .isInstanceOf(MediaValidationException.class);
    }

    @Test
    void rejectsEncryptedAndDuplicateThreeMfEntries() throws Exception {
        byte[] encrypted = validThreeMf();
        markZipEntriesEncrypted(encrypted);
        assertThatThrownBy(() -> validator.validate3mf(threeMfUpload("chair.3mf", "model/3mf", encrypted)))
                .isInstanceOf(MediaValidationException.class);

        byte[] duplicate = threeMf("3D/3dmodel.model", "<model/>".getBytes(),
                "3d/3dmodel.model", "<model/>".getBytes());
        assertThatThrownBy(() -> validator.validate3mf(threeMfUpload("chair.3mf", "model/3mf", duplicate)))
                .isInstanceOf(MediaValidationException.class);
    }

    @Test
    void rejectsThreeMfAboveCompressedLimit() throws Exception {
        properties.setPrint3mfMaxBytes(24);
        assertThatThrownBy(() -> validator.validate3mf(threeMfUpload("chair.3mf", "model/3mf", validThreeMf())))
                .isInstanceOf(MediaValidationException.class);
    }

    @Test
    void rejectsThreeMfTraversalAndExpandedSizeLimit() throws Exception {
        byte[] traversal = threeMf("../3D/3dmodel.model", new byte[]{1});
        assertThatThrownBy(() -> validator.validate3mf(threeMfUpload("chair.3mf", "model/3mf", traversal)))
                .isInstanceOf(MediaValidationException.class);

        properties.setPrint3mfMaxExpandedBytes(16);
        byte[] expanded = threeMf("3D/3dmodel.model", new byte[64]);
        assertThatThrownBy(() -> validator.validate3mf(threeMfUpload("chair.3mf", "model/3mf", expanded)))
                .isInstanceOf(MediaValidationException.class);
    }

    @Test
    void rejectsThreeMfWrongExtensionMimeAndEntryLimit() throws Exception {
        byte[] archive = validThreeMf();
        assertThatThrownBy(() -> validator.validate3mf(threeMfUpload("chair.zip", "model/3mf", archive)))
                .isInstanceOf(MediaValidationException.class);
        assertThatThrownBy(() -> validator.validate3mf(threeMfUpload("chair.3mf", "text/plain", archive)))
                .isInstanceOf(MediaValidationException.class);

        properties.setPrint3mfMaxEntries(1);
        assertThatThrownBy(() -> validator.validate3mf(threeMfUpload("chair.3mf", "model/3mf", archive)))
                .isInstanceOf(MediaValidationException.class);
    }

    private void assertRejectedGlb(MockMultipartFile glb) {
        assertThatThrownBy(() -> validator.validateGlb(glb))
                .isInstanceOf(MediaValidationException.class);
    }

    private MockMultipartFile image(String filename, String mime, String format) throws Exception {
        BufferedImage image = new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, format, output);
        return new MockMultipartFile("images", filename, mime, output.toByteArray());
    }

    private MockMultipartFile glb(String filename, String mime, String magic, int version, int declaredLength) {
        byte[] bytes = new byte[12];
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(magic.getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, 4);
        buffer.putInt(4, version);
        buffer.putInt(8, declaredLength);
        return new MockMultipartFile("model", filename, mime, bytes);
    }

    private MockMultipartFile threeMfUpload(String filename, String mime, byte[] bytes) {
        return new MockMultipartFile("printFile", filename, mime, bytes);
    }

    private byte[] validThreeMf() throws IOException {
        return threeMf("3D/3dmodel.model", "<model/>".getBytes());
    }

    private byte[] threeMf(String member, byte[] content) throws IOException {
        return threeMf(member, content, null, null);
    }

    private byte[] threeMf(String member, byte[] content, String extraMember, byte[] extraContent) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            put(zip, "[Content_Types].xml", "<Types/>".getBytes());
            put(zip, member, content);
            if (extraMember != null) put(zip, extraMember, extraContent);
        }
        return bytes.toByteArray();
    }

    private void markZipEntriesEncrypted(byte[] archive) {
        for (int i = 0; i + 8 < archive.length; i++) {
            if (archive[i] == 'P' && archive[i + 1] == 'K' && archive[i + 2] == 3 && archive[i + 3] == 4) {
                archive[i + 6] |= 1;
            } else if (archive[i] == 'P' && archive[i + 1] == 'K' && archive[i + 2] == 1 && archive[i + 3] == 2) {
                archive[i + 8] |= 1;
            }
        }
    }

    private void put(ZipOutputStream zip, String name, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }
}
