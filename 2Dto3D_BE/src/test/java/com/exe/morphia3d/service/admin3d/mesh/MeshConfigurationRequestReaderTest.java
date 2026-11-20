package com.exe.morphia3d.service.admin3d.mesh;

import com.exe.morphia3d.dto.admin3d.mesh.MeshConfigurationRequest;
import com.exe.morphia3d.service.glb.GlbStructure;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MeshConfigurationRequestReaderTest {
    private final MeshConfigurationRequestReader reader = new MeshConfigurationRequestReader();

    @Test
    void acceptsValidBodyAndRemovesSpoolOnClose() throws Exception {
        long before = spoolCount();
        try (var payload = reader.read(request(validBody()))) {
            assertThat(payload.request().mediaAssetId()).isEqualTo(41L);
            assertThat(payload.request().meshes()).hasSize(1);
            assertThat(spoolCount()).isEqualTo(before + 1);
        }
        assertThat(spoolCount()).isEqualTo(before);
    }

    @Test
    void rejectsDeclaredOversizeBeforeReadingBody() {
        MockHttpServletRequest request = new MockHttpServletRequest() {
            @Override public long getContentLengthLong() { return MeshConfigurationRequestReader.MAX_BODY_BYTES + 1; }
            @Override public int getContentLength() { return (int) MeshConfigurationRequestReader.MAX_BODY_BYTES + 1; }
            @Override public ServletInputStream getInputStream() { throw new AssertionError("body must not be read"); }
        };
        assertThatThrownBy(() -> reader.read(request))
                .isInstanceOfSatisfying(MeshConfigurationRequestReader.RequestFailure.class,
                        failure -> assertThat(failure.status()).isEqualTo(413));
    }

    @Test
    void rejectsChunkedBodyThatCrossesLimitAndCleansSpool() throws Exception {
        long before = spoolCount();
        MockHttpServletRequest request = new MockHttpServletRequest() {
            @Override public long getContentLengthLong() { return -1; }
            @Override public int getContentLength() { return -1; }
            @Override public ServletInputStream getInputStream() {
                return new ServletInputStream() {
                    private long remaining = MeshConfigurationRequestReader.MAX_BODY_BYTES + 1;
                    @Override public int read() { if (remaining-- > 0) return ' '; return -1; }
                    @Override public int read(byte[] bytes, int offset, int length) {
                        if (remaining <= 0) return -1;
                        int count = (int) Math.min(length, remaining);
                        java.util.Arrays.fill(bytes, offset, offset + count, (byte) ' ');
                        remaining -= count;
                        return count;
                    }
                    @Override public boolean isFinished() { return remaining <= 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener readListener) { throw new UnsupportedOperationException(); }
                };
            }
        };
        assertThatThrownBy(() -> reader.read(request))
                .isInstanceOfSatisfying(MeshConfigurationRequestReader.RequestFailure.class,
                        failure -> assertThat(failure.status()).isEqualTo(413));
        assertThat(spoolCount()).isEqualTo(before);
    }

    @Test
    void rejectsDuplicateUnknownMissingNullAndWrongTypedFields() throws Exception {
        long before = spoolCount();
        for (String body : new String[]{
                validBody().replace("\"mediaAssetId\":41", "\"mediaAssetId\":41,\"mediaAssetId\":41"),
                validBody().replace("\"mediaAssetId\":41", "\"unexpected\":1,\"mediaAssetId\":41"),
                validBody().replace("\"nodeIndex\":0", "\"nodeIndex\":0,\"nodeIndex\":0"),
                validBody().replace("\"allowedColorIds\":[5]", "\"allowedColorIds\":[5],\"unexpected\":1"),
                "{}", validBody().replace("\"materialId\":3", "\"materialId\":null"),
                validBody().replace("\"nodeIndex\":0", "\"nodeIndex\":\"zero\"")}) {
            assertRejected(body);
        }
        assertThat(spoolCount()).isEqualTo(before);
    }

    @Test
    void rejectsScalarCoercionForNumbersAndBooleans() {
        for (String body : new String[]{
                validBody().replace("\"mediaAssetId\":41", "\"mediaAssetId\":\"41\""),
                validBody().replace("\"expectedRevision\":0", "\"expectedRevision\":\"0\""),
                validBody().replace("\"materialId\":3", "\"materialId\":3.0"),
                validBody().replace("\"nodeIndex\":0", "\"nodeIndex\":\"0\""),
                validBody().replace("\"customizable\":true", "\"customizable\":\"true\"")}) {
            assertRejected(body);
        }
    }

    @Test
    void rejectsInvalidEnvelopeIdsAndNegativeRevisionButAcceptsLongMaxAsAnInteger() {
        for (String body : new String[]{
                validBody().replace("\"mediaAssetId\":41", "\"mediaAssetId\":0"),
                validBody().replace("\"materialId\":3", "\"materialId\":0"),
                validBody().replace("\"expectedRevision\":0", "\"expectedRevision\":-1")}) {
            assertRejected(body);
        }
        try (var payload = reader.read(request(validBody().replace("\"expectedRevision\":0",
                "\"expectedRevision\":9223372036854775807")))) {
            assertThat(payload.request().expectedRevision()).isEqualTo(Long.MAX_VALUE);
        }
    }

    @Test
    void rejectsUnsupportedContentEncoding() {
        MockHttpServletRequest request = request(validBody());
        request.addHeader("Content-Encoding", "gzip");
        assertThatThrownBy(() -> reader.read(request))
                .isInstanceOfSatisfying(MeshConfigurationRequestReader.RequestFailure.class,
                        failure -> assertThat(failure.status()).isEqualTo(400));
    }

    @Test
    void handlesZeroProgressReadsAndReleasesAdmissionAfterClose() {
        byte[] body = validBody().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MockHttpServletRequest zeroProgress = new MockHttpServletRequest() {
            @Override public long getContentLengthLong() { return -1; }
            @Override public int getContentLength() { return -1; }
            @Override public ServletInputStream getInputStream() {
                InputStream delegate = new java.io.ByteArrayInputStream(body);
                return new ServletInputStream() {
                    private boolean zeroOnce = true;
                    @Override public int read() throws java.io.IOException { return delegate.read(); }
                    @Override public int read(byte[] bytes, int offset, int length) throws java.io.IOException {
                        if (zeroOnce) { zeroOnce = false; return 0; }
                        return delegate.read(bytes, offset, length);
                    }
                    @Override public boolean isFinished() { return false; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
                };
            }
        };
        try (var first = reader.read(zeroProgress)) {
            assertThat(first.request().mediaAssetId()).isEqualTo(41L);
            var second = reader.read(request(validBody()));
            try {
                assertThat(second.request().mediaAssetId()).isEqualTo(41L);
                assertThatThrownBy(() -> reader.read(request(validBody())))
                        .isInstanceOfSatisfying(MeshConfigurationRequestReader.RequestFailure.class,
                                failure -> assertThat(failure.status()).isEqualTo(503));
            } finally {
                second.close();
            }
            try (var next = reader.read(request(validBody()))) {
                assertThat(next.request().mediaAssetId()).isEqualTo(41L);
            }
        }
        try (var next = reader.read(request(validBody()))) {
            assertThat(next.request().mediaAssetId()).isEqualTo(41L);
        }
    }

    @Test
    void acceptsStageOneMaximumOccurrencesAndAggregateColors() {
        String snapshot = UUID.randomUUID().toString();
        StringBuilder body = new StringBuilder(18_000_000)
                .append("{\"mediaAssetId\":41,\"structureSnapshotId\":\"").append(snapshot)
                .append("\",\"expectedRevision\":0,\"materialId\":3,\"meshes\":[");
        for (int i = 0; i < 100_000; i++) {
            if (i != 0) body.append(',');
            body.append("{\"nodeIndex\":").append(i)
                    .append(",\"meshIndex\":0,\"primitiveIndex\":0,\"customizable\":true,\"defaultColorId\":5,\"allowedColorIds\":[5]}");
        }
        body.append("]}");
        assertThat(body.length()).isLessThan((int) MeshConfigurationRequestReader.MAX_BODY_BYTES);
        try (var payload = reader.read(request(body.toString()))) {
            assertThat(payload.request().meshes()).hasSize(100_000);
            assertThat(payload.request().meshes().stream().mapToLong(mesh -> mesh.allowedColorIds().size()).sum())
                    .isEqualTo(100_000L);
            GlbStructure structure = new GlbStructure(-1,
                    java.util.List.of(),
                    java.util.stream.IntStream.range(0, 100_000)
                            .mapToObj(index -> new GlbStructure.Node(index, null, 0, java.util.List.of(), java.util.List.of())).toList(),
                    java.util.List.of(new GlbStructure.Mesh(0, null,
                            java.util.List.of(new GlbStructure.Primitive(0, null, java.util.List.of(), java.util.List.of())))),
                    java.util.List.of(), java.util.List.of(), java.util.List.of(),
                    java.util.stream.IntStream.range(0, 100_000)
                            .mapToObj(index -> new GlbStructure.PrimitiveOccurrence(index, 0, 0, null, null)).toList());
            new MeshConfigurationValidator().validate(7, 41L, UUID.fromString(snapshot), payload.request(), structure);
        }
    }

    private static MockHttpServletRequest request(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContentType("application/json");
        request.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return request;
    }

    private static String validBody() {
        return "{\"mediaAssetId\":41,\"structureSnapshotId\":\"" + UUID.randomUUID()
                + "\",\"expectedRevision\":0,\"materialId\":3,\"meshes\":[{\"nodeIndex\":0,\"meshIndex\":0,\"primitiveIndex\":0,\"customizable\":true,\"defaultColorId\":5,\"allowedColorIds\":[5]}]}";
    }

    private void assertRejected(String body) {
        MeshConfigurationRequestReader.Payload payload = null;
        try {
            payload = reader.read(request(body));
            throw new AssertionError("Expected request body to be rejected");
        } catch (MeshConfigurationRequestReader.RequestFailure expected) {
            // Expected boundary rejection.
        } finally {
            if (payload != null) payload.close();
        }
    }

    private static long spoolCount() throws Exception {
        try (var files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return files.filter(path -> path.getFileName().toString().startsWith("admin-mesh-request-")).count();
        }
    }
}


