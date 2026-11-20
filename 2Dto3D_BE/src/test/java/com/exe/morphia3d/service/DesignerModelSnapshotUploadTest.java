package com.exe.morphia3d.service;

import com.exe.morphia3d.dto.request.DesignerModelRequest;
import com.exe.morphia3d.dto.response.ModelResponse;
import com.exe.morphia3d.entity.ProductGlbStructureSnapshot;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.MediaKind;
import com.exe.morphia3d.enums.MediaProvider;
import com.exe.morphia3d.enums.Role;
import com.exe.morphia3d.security.UserPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DesignerModelSnapshotUploadTest {
    @TempDir Path spoolDirectory;

    @Mock private MediaStorageService storage;
    @Mock private MediaAssetLifecycleService lifecycle;
    @Mock private MediaUploadHeartbeat heartbeat;
    @Mock private DesignerModelPersistenceService persistence;

    private MediaUploadProperties properties;
    private DesignerModelUploadService service;
    private UserPrincipal principal;
    private DesignerModelRequest metadata;
    private List<MultipartFile> images;
    private MultipartFile model;
    private MediaAssetOperationLease operation;
    private MediaUploadIntent modelIntent;
    private MediaUploadIntent imageIntent;
    private final AtomicReference<byte[]> uploadedModel = new AtomicReference<>();
    private final AtomicReference<String> uploadedDigest = new AtomicReference<>();
    private final AtomicReference<String> spoolDigest = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        properties = new MediaUploadProperties();
        properties.setSnapshotTempDirectory(spoolDirectory.toString());
        properties.setSnapshotMaxConcurrent(1);
        properties.setModelMaxBytes(1024 * 1024);
        service = new DesignerModelUploadService(new MediaValidationService(properties), storage, lifecycle,
                heartbeat, persistence, properties);
        principal = new UserPrincipal(User.builder().id(42).role(Role.DESIGNER).build());
        metadata = new DesignerModelRequest();
        metadata.setName("Snapshot lamp");
        images = List.of(new MockMultipartFile("images", "front.png", "image/png", png()));
        model = new MockMultipartFile("model", "model.glb", "model/gltf-binary", validGlb());
        operation = new MediaAssetOperationLease(UUID.randomUUID(), UUID.randomUUID(), "upload-owner", List.of(101L, 102L));
        modelIntent = new MediaUploadIntent(101L, operation.operationId(), UUID.randomUUID(), MediaKind.MODEL_3D);
        imageIntent = new MediaUploadIntent(102L, operation.operationId(), UUID.randomUUID(), MediaKind.IMAGE);

        when(lifecycle.createUploadIntents(any(), anyList())).thenReturn(operation);
        when(lifecycle.claimUpload(operation, 101L)).thenReturn(modelIntent);
        when(lifecycle.claimUpload(operation, 102L)).thenReturn(imageIntent);
        doAnswer(invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(1)).get())
                .when(heartbeat).during(any(), any());
        when(storage.upload(eq(modelIntent), any())).thenAnswer(invocation -> {
            ValidatedMedia media = invocation.getArgument(1);
            spoolDigest.set(((SnapshotGlbSpool.FileBackedMultipartFile) media.file()).sha256Hex());
            try (InputStream input = media.file().getInputStream()) {
                byte[] bytes = input.readAllBytes();
                uploadedModel.set(bytes);
                uploadedDigest.set(sha256(bytes));
            }
            return result(modelIntent);
        });
        when(storage.upload(eq(imageIntent), any())).thenReturn(result(imageIntent));
        when(persistence.persistWithSnapshot(eq(principal), eq(metadata), eq(operation),
                eq(ProductGlbStructureSnapshot.CURRENT_PARSER_VERSION), any(JsonNode.class)))
                .thenReturn(ModelResponse.builder().id(700).build());
    }

    @Test
    void parsesAndUploadsTheSameTrustedSpoolBeforeCreatingSnapshotPersistence() throws Exception {
        byte[] original = ((MockMultipartFile) model).getBytes();

        ModelResponse response = service.createWithSnapshot(principal, metadata, images, model);

        assertThat(response.getId()).isEqualTo(700);
        assertThat(uploadedModel.get()).containsExactly(original);
        assertThat(uploadedDigest.get()).isEqualTo(sha256(original));
        assertThat(uploadedDigest.get()).isEqualTo(spoolDigest.get());
        org.mockito.ArgumentCaptor<JsonNode> snapshot = org.mockito.ArgumentCaptor.forClass(JsonNode.class);
        verify(persistence).persistWithSnapshot(eq(principal), eq(metadata), eq(operation),
                eq(ProductGlbStructureSnapshot.CURRENT_PARSER_VERSION), snapshot.capture());
        assertThat(snapshot.getValue().get("defaultSceneIndex").intValue()).isZero();
        assertThat(snapshot.getValue().get("scenes")).hasSize(1);
        assertThat(snapshot.getValue().get("nodes").get(0).get("meshIndex").intValue()).isZero();
        assertThat(snapshot.getValue().get("meshes").get(0).get("primitives")).hasSize(1);
        assertThat(snapshot.getValue().get("primitiveOccurrences").get(0).get("nodeIndex").intValue()).isZero();
        assertThat(snapshot.getValue().get("primitiveOccurrences").get(0).get("meshIndex").intValue()).isZero();
        assertThat(snapshot.getValue().get("primitiveOccurrences").get(0).get("primitiveIndex").intValue()).isZero();
        verify(lifecycle).createUploadIntents(any(), anyList());
        assertSpoolClean();
    }

    @Test
    void malformedGlbFailsBeforeMediaIntentProviderOrPersistence() throws IOException {
        byte[] headerOnly = new byte[]{'g', 'l', 'T', 'F', 2, 0, 0, 0, 12, 0, 0, 0};
        MultipartFile malformed = new MockMultipartFile("model", "broken.glb", "model/gltf-binary", headerOnly);

        assertThatThrownBy(() -> service.createWithSnapshot(principal, metadata, images, malformed))
                .isInstanceOf(MediaValidationException.class)
                .extracting(error -> ((MediaValidationException) error).getReason())
                .isEqualTo(MediaValidationException.Reason.INVALID_GLB);

        verify(lifecycle, never()).createUploadIntents(any(), anyList());
        verify(storage, never()).upload(any(), any());
        verify(persistence, never()).persistWithSnapshot(any(), any(), any(), any(), any());
        assertSpoolClean();
    }

    @Test
    void legacyHeaderOnlyGlbStillUsesLegacyPersistenceWithoutSnapshot() {
        byte[] headerOnly = new byte[]{'g', 'l', 'T', 'F', 2, 0, 0, 0, 12, 0, 0, 0};
        MultipartFile legacy = new MockMultipartFile("model", "legacy.glb", "model/gltf-binary", headerOnly);
        org.mockito.Mockito.doReturn(result(modelIntent)).when(storage).upload(eq(modelIntent), any());

        service.create(principal, metadata, images, legacy);

        verify(persistence).persist(principal, metadata, operation);
        verify(persistence, never()).persistWithSnapshot(any(), any(), any(), any(), any());
    }

    @Test
    void unavailableTemporaryDirectoryFailsBeforeMediaIntentCreation() throws IOException {
        properties.setSnapshotTempDirectory(spoolDirectory.resolve("missing").toString());

        assertThatThrownBy(() -> service.createWithSnapshot(principal, metadata, images, model))
                .isInstanceOf(SnapshotPreparationException.class);

        verify(lifecycle, never()).createUploadIntents(any(), anyList());
        verify(storage, never()).upload(any(), any());
        assertSpoolClean();
    }

    @Test
    void uncheckedSourceReadFailureDeletesPartialSpoolAndReleasesAdmissionForNextRequest() throws Exception {
        byte[] sourceBytes = validGlb();
        RuntimeException sourceFailure = new IllegalStateException("source stream failed after partial read");
        MultipartFile broken = multipartWithStream(sourceBytes, new InputStream() {
            private boolean partialRead;

            @Override
            public int read(byte[] target, int offset, int length) {
                if (partialRead) throw sourceFailure;
                partialRead = true;
                int count = Math.min(13, sourceBytes.length);
                System.arraycopy(sourceBytes, 0, target, offset, count);
                return count;
            }

            @Override
            public int read() {
                throw sourceFailure;
            }
        });

        assertThatThrownBy(() -> service.createWithSnapshot(principal, metadata, images, broken))
                .isSameAs(sourceFailure);

        assertSpoolClean();
        verify(lifecycle, never()).createUploadIntents(any(), anyList());
        verify(storage, never()).upload(any(), any());
        verify(persistence, never()).persistWithSnapshot(any(), any(), any(), any(), any());

        service.createWithSnapshot(principal, metadata, images, model);

        verify(lifecycle).createUploadIntents(any(), anyList());
        verify(storage).upload(eq(modelIntent), any());
        verify(storage).upload(eq(imageIntent), any());
        verify(persistence).persistWithSnapshot(eq(principal), eq(metadata), eq(operation), any(), any());
        assertSpoolClean();
    }

    @Test
    void zeroLengthBulkReadFallsBackToSingleByteProgress() throws Exception {
        byte[] sourceBytes = validGlb();
        AtomicInteger bulkReads = new AtomicInteger();
        AtomicInteger singleReads = new AtomicInteger();
        AtomicInteger offset = new AtomicInteger();
        MultipartFile zeroReadOnce = multipartWithStream(sourceBytes, new InputStream() {
            @Override
            public int read(byte[] target, int targetOffset, int length) {
                if (bulkReads.getAndIncrement() == 0) return 0;
                int position = offset.get();
                if (position == sourceBytes.length) return -1;
                int count = Math.min(length, sourceBytes.length - position);
                System.arraycopy(sourceBytes, position, target, targetOffset, count);
                offset.addAndGet(count);
                return count;
            }

            @Override
            public int read() {
                singleReads.incrementAndGet();
                int position = offset.getAndIncrement();
                return position == sourceBytes.length ? -1 : sourceBytes[position] & 0xff;
            }
        });

        try (SnapshotGlbSpool spool = SnapshotGlbSpool.create(zeroReadOnce, spoolDirectory, 1024 * 1024);
             InputStream result = spool.file().getInputStream()) {
            assertThat(result.readAllBytes()).containsExactly(sourceBytes);
        }

        assertThat(singleReads).hasValue(1);
        assertSpoolClean();
    }

    @Test
    void oversizedGlbFailsBeforeMediaIntentProviderOrPersistence() throws IOException {
        properties.setModelMaxBytes(64);
        byte[] original = validGlb();
        MultipartFile oversized = new MockMultipartFile("model", "large.glb", "model/gltf-binary", original);

        assertThatThrownBy(() -> service.createWithSnapshot(principal, metadata, images, oversized))
                .isInstanceOf(MediaValidationException.class)
                .extracting(error -> ((MediaValidationException) error).getReason())
                .isEqualTo(MediaValidationException.Reason.TOO_LARGE);

        verify(lifecycle, never()).createUploadIntents(any(), anyList());
        verify(storage, never()).upload(any(), any());
        assertSpoolClean();
    }

    @Test
    void uploadFailureDeletesSpoolAndRequestsSafeCleanup() throws IOException {
        RuntimeException failure = new MediaStorageException(MediaStorageException.Reason.PROVIDER_UNAVAILABLE);
        when(storage.upload(eq(modelIntent), any())).thenThrow(failure);

        assertThatThrownBy(() -> service.createWithSnapshot(principal, metadata, images, model))
                .isSameAs(failure);

        verify(lifecycle).requestCleanup(operation, operation.mediaAssetIds());
        verify(persistence, never()).persistWithSnapshot(any(), any(), any(), any(), any());
        assertSpoolClean();
    }

    @Test
    void persistenceFailureDeletesSpoolAndCompensatesOnlyWhenRollbackIsKnown() throws IOException {
        when(persistence.persistWithSnapshot(eq(principal), eq(metadata), eq(operation), any(), any()))
                .thenThrow(MediaPersistenceException.rolledBack(new IllegalStateException("rollback")));

        assertThatThrownBy(() -> service.createWithSnapshot(principal, metadata, images, model))
                .isInstanceOf(MediaPersistenceException.class);

        verify(lifecycle).requestCleanup(operation, operation.mediaAssetIds());
        assertSpoolClean();
    }

    @Test
    void ambiguousPersistenceOutcomeDoesNotRequestMediaCleanup() throws IOException {
        when(persistence.persistWithSnapshot(eq(principal), eq(metadata), eq(operation), any(), any()))
                .thenThrow(MediaPersistenceException.outcomeUnknown(new IllegalStateException("commit unknown")));

        assertThatThrownBy(() -> service.createWithSnapshot(principal, metadata, images, model))
                .isInstanceOf(MediaPersistenceException.class);

        verify(lifecycle, never()).requestCleanup(any(), anyList());
        assertThat(Files.list(spoolDirectory).toList()).isEmpty();
    }

    @Test
    void configuredAdmissionLimitRejectsConcurrentSnapshotSpoolBeforeIntents() throws Exception {
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        when(storage.upload(eq(modelIntent), any())).thenAnswer(invocation -> {
            providerEntered.countDown();
            assertThat(releaseProvider.await(10, TimeUnit.SECONDS)).isTrue();
            return result(modelIntent);
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> first = executor.submit(() -> service.createWithSnapshot(principal, metadata, images, model));
            assertThat(providerEntered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> service.createWithSnapshot(principal, metadata, images, model))
                    .isInstanceOf(SnapshotPreparationException.class);
            verify(lifecycle).createUploadIntents(any(), anyList());
            releaseProvider.countDown();
            first.get(10, TimeUnit.SECONDS);
            assertSpoolClean();
        } finally {
            releaseProvider.countDown();
            executor.shutdownNow();
        }
    }

    private MediaUploadResult result(MediaUploadIntent intent) {
        String format = intent.mediaKind() == MediaKind.MODEL_3D ? "glb" : "png";
        return new MediaUploadResult(MediaProvider.CLOUDINARY, "test-cloud", "asset-" + intent.mediaAssetId(),
                intent.publicId(), intent.mediaKind(), intent.resourceType(), intent.deliveryType(), format,
                1L, 1L, "https://res.cloudinary.com/test-cloud/" + intent.resourceType()
                + "/upload/v1/" + intent.publicId());
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private MultipartFile multipartWithStream(byte[] bytes, InputStream input) {
        return new MultipartFile() {
            @Override public String getName() { return "model"; }
            @Override public String getOriginalFilename() { return "model.glb"; }
            @Override public String getContentType() { return "model/gltf-binary"; }
            @Override public boolean isEmpty() { return false; }
            @Override public long getSize() { return bytes.length; }
            @Override public byte[] getBytes() { throw new UnsupportedOperationException(); }
            @Override public InputStream getInputStream() { return input; }
            @Override public void transferTo(File destination) throws IOException {
                try (OutputStream output = Files.newOutputStream(destination.toPath())) {
                    output.write(bytes);
                }
            }
        };
    }

    private void assertSpoolClean() throws IOException {
        try (var files = Files.list(spoolDirectory)) {
            assertThat(files.toList()).isEmpty();
        }
    }

    private static byte[] png() throws IOException {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private static byte[] validGlb() throws IOException {
        byte[] json = ("{\"asset\":{\"version\":\"2.0\"},\"scene\":0,"
                + "\"scenes\":[{\"nodes\":[0]}],\"nodes\":[{\"name\":\"Lamp body\",\"mesh\":0}],"
                + "\"meshes\":[{\"name\":\"Body mesh\",\"primitives\":[{\"attributes\":{\"POSITION\":0}}]}],"
                + "\"accessors\":[{\"componentType\":5126,\"count\":1,\"type\":\"VEC3\",\"min\":[0,0,0],\"max\":[0,0,0]}],"
                + "\"buffers\":[{\"byteLength\":4}]}" ).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int paddedJson = (json.length + 3) & ~3;
        int totalLength = 12 + 8 + paddedJson + 8 + 4;
        java.nio.ByteBuffer result = java.nio.ByteBuffer.allocate(totalLength).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        result.put(new byte[]{'g', 'l', 'T', 'F'}).putInt(2).putInt(totalLength);
        result.putInt(paddedJson).putInt(0x4e4f534a).put(json);
        while (result.position() < 12 + 8 + paddedJson) result.put((byte) ' ');
        result.putInt(4).putInt(0x004e4942).put(new byte[]{0, 0, 0, 0});
        return result.array();
    }
}
