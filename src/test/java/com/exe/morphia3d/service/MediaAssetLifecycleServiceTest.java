package com.exe.morphia3d.service;

import com.exe.morphia3d.entity.MediaAsset;
import com.exe.morphia3d.entity.Model3d;
import com.exe.morphia3d.entity.ModelImage;
import com.exe.morphia3d.enums.MediaAssetState;
import com.exe.morphia3d.enums.MediaKind;
import com.exe.morphia3d.enums.MediaProvider;
import com.exe.morphia3d.enums.ModelType;
import com.exe.morphia3d.repository.MediaAssetRepository;
import com.exe.morphia3d.repository.Model3dRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(MediaAssetLifecycleServiceTest.StorageTestConfiguration.class)
class MediaAssetLifecycleServiceTest {

    @Autowired
    private MediaAssetLifecycleService lifecycle;

    @Autowired
    private MediaAssetRepository assets;

    @Autowired
    private Model3dRepository modelRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MediaAssetReconciler reconciler;

    @Autowired
    private MediaLifecycleProperties lifecycleProperties;

    @Autowired
    private RecordingMediaStorageService mediaStorage;

    private final Map<UUID, MediaAssetOperationLease> operationLeases = new HashMap<>();

    @BeforeEach
    void isolateMediaRows() {
        jdbc.update("DELETE FROM model_images WHERE media_asset_id IS NOT NULL");
        jdbc.update("DELETE FROM model_3d WHERE media_asset_id IS NOT NULL");
        jdbc.update("DELETE FROM media_asset");
        operationLeases.clear();
        mediaStorage.clear();
    }

    @Test
    void refreshesOperationLeaseToProtectLaterPendingAssetsAndNeverDeletesThem() {
        MediaAssetOperationLease operation = createOperation(UUID.randomUUID(),
                new MediaAssetIntentSpec(MediaKind.MODEL_3D, "glb", 12L),
                new MediaAssetIntentSpec(MediaKind.IMAGE, "png", 3L),
                new MediaAssetIntentSpec(MediaKind.IMAGE, "webp", 4L));
        long activeId = operation.mediaAssetIds().get(0);
        MediaUploadIntent activeUpload = lifecycle.claimUpload(operation, activeId);
        lifecycle.refreshUploadLease(activeUpload);
        jdbc.update("UPDATE media_asset SET created_at = ?, updated_at = ? WHERE operation_id = ?",
                Instant.now().minusSeconds(600), Instant.now().minusSeconds(600), operation.operationId());

        assertThat(lifecycle.recoverStaleUploadIntents()).isZero();
        assertThat(findState(operation.mediaAssetIds().get(1))).isEqualTo(MediaAssetState.PENDING_UPLOAD);
        assertThat(reconciler.reconcileOnce()).isZero();
        assertThat(mediaStorage.deleted()).isEmpty();
    }

    @Test
    void recoversAnAbandonedMultiAssetOperationAndRejectsItsStaleLease() {
        MediaAssetOperationLease operation = createOperation(UUID.randomUUID(),
                new MediaAssetIntentSpec(MediaKind.MODEL_3D, "glb", 12L),
                new MediaAssetIntentSpec(MediaKind.IMAGE, "png", 3L));
        MediaUploadIntent upload = lifecycle.claimUpload(operation, operation.mediaAssetIds().get(0));
        lifecycle.recordUpload(upload, result(upload));
        jdbc.update("UPDATE media_asset SET lease_until = ?, created_at = ?, updated_at = ? WHERE operation_id = ?",
                Instant.now().minusSeconds(600), Instant.now().minusSeconds(600), Instant.now().minusSeconds(600),
                operation.operationId());

        assertThat(lifecycle.recoverStaleUploadIntents()).isEqualTo(2);
        assertThat(findStates(operation.mediaAssetIds()))
                .containsOnly(MediaAssetState.CLEANUP_PENDING);
        assertThatThrownBy(() -> lifecycle.refreshUploadLease(upload)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> lifecycle.requestCleanup(operation, operation.mediaAssetIds()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void abandonedOperationBecomesCleanupEligibleOnlyAfterRecoveryGrace() {
        MediaAssetOperationLease operation = createOperation(UUID.randomUUID(),
                new MediaAssetIntentSpec(MediaKind.MODEL_3D, "glb", 12L),
                new MediaAssetIntentSpec(MediaKind.IMAGE, "png", 3L));
        jdbc.update("UPDATE media_asset SET lease_until = ?, created_at = ?, updated_at = ? WHERE operation_id = ?",
                Instant.now().minusSeconds(600), Instant.now().minusSeconds(600), Instant.now().minusSeconds(600),
                operation.operationId());
        lifecycle.recoverStaleUploadIntents();
        jdbc.update("UPDATE media_asset SET lease_until = ? WHERE operation_id = ?",
                Instant.now().minusSeconds(600), operation.operationId());
        assertThat(reconciler.reconcileOnce()).isEqualTo(2);
        assertThat(findStates(operation.mediaAssetIds())).containsOnly(MediaAssetState.DELETED);
        assertThat(mediaStorage.deleted()).hasSize(2);
    }

    @Test
    void cleanupCandidatePageSkipsBackoffIneligibleRows() {
        lifecycleProperties.setBatchSize(1);
        try {
            UUID blockedOperationId = UUID.randomUUID();
            long blockedId = createSingleAsset(blockedOperationId, MediaKind.IMAGE, "png", 3L);
            requestCleanup(blockedOperationId, List.of(blockedId));

            UUID eligibleOperationId = UUID.randomUUID();
            long eligibleId = createSingleAsset(eligibleOperationId, MediaKind.IMAGE, "webp", 4L);
            requestCleanup(eligibleOperationId, List.of(eligibleId));
            jdbc.update("UPDATE media_asset SET lease_until = ? WHERE id = ?",
                    Instant.now().minusSeconds(1), eligibleId);

            assertThat(lifecycle.cleanupCandidateIds()).containsExactly(eligibleId);
            assertThat(lifecycle.claimCleanup(eligibleId, "eligible-worker")).isNotNull();
            assertThat(findState(blockedId)).isEqualTo(MediaAssetState.CLEANUP_PENDING);
        } finally {
            lifecycleProperties.setBatchSize(100);
        }
    }

    @Test
    void enforcesPendingUploadingReadyAttachedTransitionsAndRejectsWrongToken() {
        UUID operationId = UUID.randomUUID();
        long id = createSingleAsset(operationId, MediaKind.IMAGE, "png", 3L);
        MediaUploadIntent claim = claimSingleAsset(id);
        assertThat(claim).isNotNull();
        assertThat(claimSingleAsset(id)).isNull();

        MediaUploadIntent staleToken = new MediaUploadIntent(id, operationId, UUID.randomUUID(), MediaKind.IMAGE);
        assertThatThrownBy(() -> lifecycle.recordUpload(staleToken, result(claim)))
                .isInstanceOf(IllegalStateException.class);

        lifecycle.recordUpload(claim, result(claim));
        MediaAsset ready = assets.findById(id).orElseThrow();
        assertThat(ready.getState()).isEqualTo(MediaAssetState.READY);

        Model3d model = Model3d.builder().sourceType(ModelType.DESIGNER_MODEL)
                .filePath3d(claim.publicId()).mediaAsset(ready).build();
        modelRepository.saveAndFlush(model);
        attach(operationId, List.of(id));

        assertThat(assets.findById(id).orElseThrow().getState()).isEqualTo(MediaAssetState.ATTACHED);
        assertThatThrownBy(() -> requestCleanup(operationId, List.of(id)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(lifecycle.claimCleanup(id, "worker-1")).isNull();
    }

    @Test
    void refreshesLongUploadLeaseOnlyForCurrentUnexpiredToken() {
        long id = createSingleAsset(UUID.randomUUID(), MediaKind.MODEL_3D, "glb", 12L);
        MediaUploadIntent claim = claimSingleAsset(id);
        Instant oldLease = assets.findById(id).orElseThrow().getLeaseUntil();

        lifecycle.refreshUploadLease(claim);

        assertThat(assets.findById(id).orElseThrow().getLeaseUntil()).isAfter(oldLease);
        MediaUploadIntent wrong = new MediaUploadIntent(id, claim.operationId(), UUID.randomUUID(), MediaKind.MODEL_3D);
        assertThatThrownBy(() -> lifecycle.refreshUploadLease(wrong)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cleanupClaimWaitsForGraceAndIsExclusive() {
        UUID operationId = UUID.randomUUID();
        long id = createSingleAsset(operationId, MediaKind.IMAGE, "png", 3L);
        requestCleanup(operationId, List.of(id));

        assertThat(lifecycle.claimCleanup(id, "worker-early")).isNull();
        jdbc.update("UPDATE media_asset SET lease_until = ? WHERE id = ?",
                Instant.now().minusSeconds(600), id);

        MediaAssetCleanupLease first = lifecycle.claimCleanup(id, "worker-first");
        MediaAssetCleanupLease second = lifecycle.claimCleanup(id, "worker-second");

        assertThat(first).isNotNull();
        assertThat(second).isNull();
    }

    @Test
    void cleanupDoesNotClaimAnAssetWithAnExistingReference() {
        UUID operationId = UUID.randomUUID();
        long id = createSingleAsset(operationId, MediaKind.IMAGE, "png", 3L);
        MediaUploadIntent claim = claimSingleAsset(id);
        lifecycle.recordUpload(claim, result(claim));
        MediaAsset asset = assets.findById(id).orElseThrow();
        Model3d model = Model3d.builder().sourceType(ModelType.DESIGNER_MODEL)
                .filePath3d(claim.publicId()).mediaAsset(asset).build();
        modelRepository.saveAndFlush(model);
        // Simulate an inconsistent historical row; destructive reconciliation must still fail closed.
        jdbc.update("UPDATE media_asset SET state = 'CLEANUP_PENDING', lease_until = ? WHERE id = ?",
                Instant.now().minusSeconds(600), id);

        assertThat(lifecycle.claimCleanup(id, "worker-ref")).isNull();
    }

    @Test
    void recoversExpiredUploadOnlyAfterLeaseAndGraceAndKeepsMissingAssetIdRecoverable() {
        UUID operationId = UUID.randomUUID();
        long id = createSingleAsset(operationId, MediaKind.MODEL_3D, "glb", 12L);
        MediaUploadIntent claim = claimSingleAsset(id);
        assertThat(lifecycle.recoverStaleUploadIntents()).isZero();
        assertThat(requestCleanup(operationId, List.of(id))).isZero();
        jdbc.update("UPDATE media_asset SET lease_until = ?, provider_asset_id = NULL, " +
                        "updated_at = ? WHERE id = ?",
                Instant.now().minusSeconds(600), Instant.now().minusSeconds(600), id);

        assertThat(lifecycle.recoverStaleUploadIntents()).isEqualTo(1);
        MediaAsset recovered = assets.findById(id).orElseThrow();
        assertThat(recovered.getState()).isEqualTo(MediaAssetState.CLEANUP_PENDING);
        assertThat(recovered.getProviderAssetId()).isNull();
        assertThat(recovered.getPublicId()).isEqualTo(claim.publicId());
        assertThat(lifecycle.claimCleanup(id, "worker-recovery")).isNotNull();
    }

    @Test
    void cleanupLeaseIdentityMustBeFreshAndTokenOwned() {
        UUID operationId = UUID.randomUUID();
        long id = createSingleAsset(operationId, MediaKind.IMAGE, "png", 3L);
        requestCleanup(operationId, List.of(id));
        jdbc.update("UPDATE media_asset SET lease_until = ? WHERE id = ?", Instant.now().minusSeconds(600), id);
        MediaAssetCleanupLease lease = lifecycle.claimCleanup(id, "worker-safe");

        assertThat(lease).isNotNull();
        assertThat(lifecycle.verifyCleanupLease(lease).publicId())
                .isEqualTo("product-media/images/" + operationId + "/" + id);
        MediaAssetCleanupLease stale = new MediaAssetCleanupLease(id, operationId, UUID.randomUUID());
        assertThatThrownBy(() -> lifecycle.verifyCleanupLease(stale)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void readyAssetCanBeSafelyCleanedAndDeletedStateIsTerminal() {
        UUID operationId = UUID.randomUUID();
        long id = createSingleAsset(operationId, MediaKind.IMAGE, "png", 3L);
        MediaUploadIntent upload = claimSingleAsset(id);
        lifecycle.recordUpload(upload, result(upload));
        assertThat(requestCleanup(operationId, List.of(id))).isEqualTo(1);
        jdbc.update("UPDATE media_asset SET lease_until = ? WHERE id = ?", Instant.now().minusSeconds(600), id);

        MediaAssetCleanupLease cleanup = lifecycle.claimCleanup(id, "worker-delete");
        lifecycle.finishCleanup(cleanup, DeleteResult.DELETED);

        assertThat(assets.findById(id).orElseThrow().getState()).isEqualTo(MediaAssetState.DELETED);
        assertThat(lifecycle.claimCleanup(id, "worker-again")).isNull();
        assertThatThrownBy(() -> requestCleanup(operationId, List.of(id)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void expiredCleanupLeaseRejectsLateWorkerAndReturnsToPendingForRetry() {
        UUID operationId = UUID.randomUUID();
        long id = createSingleAsset(operationId, MediaKind.IMAGE, "png", 3L);
        requestCleanup(operationId, List.of(id));
        jdbc.update("UPDATE media_asset SET lease_until = ? WHERE id = ?", Instant.now().minusSeconds(600), id);
        MediaAssetCleanupLease lease = lifecycle.claimCleanup(id, "worker-expired");
        jdbc.update("UPDATE media_asset SET lease_until = ? WHERE id = ?", Instant.now().minusSeconds(1), id);

        assertThatThrownBy(() -> lifecycle.finishCleanup(lease, DeleteResult.DELETED))
                .isInstanceOf(IllegalStateException.class);
        assertThat(lifecycle.recoverExpiredCleanupLeases()).isEqualTo(1);
        assertThat(assets.findById(id).orElseThrow().getState()).isEqualTo(MediaAssetState.CLEANUP_PENDING);
        assertThat(lifecycle.claimCleanup(id, "worker-retry")).isNull();
    }

    @Test
    void rejectsProviderMetadataThatDoesNotMatchIntent() {
        long id = createSingleAsset(UUID.randomUUID(), MediaKind.IMAGE, "png", 3L);
        MediaUploadIntent upload = claimSingleAsset(id);
        MediaUploadResult wrongPublicId = new MediaUploadResult(MediaProvider.CLOUDINARY, "test-cloud", "asset-" + id,
                "untrusted/client-path", MediaKind.IMAGE, "image", "upload", "png", 3L, 1L,
                "https://res.cloudinary.com/test-cloud/image/upload/v1/untrusted/client-path.png");

        assertThatThrownBy(() -> lifecycle.recordUpload(upload, wrongPublicId))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(assets.findById(id).orElseThrow().getState()).isEqualTo(MediaAssetState.UPLOADING);
    }

    @Test
    void backsOffAndPersistsOnlySanitizedCleanupFailureCodes() {
        UUID operationId = UUID.randomUUID();
        long id = createSingleAsset(operationId, MediaKind.IMAGE, "png", 3L);
        requestCleanup(operationId, List.of(id));
        jdbc.update("UPDATE media_asset SET lease_until = ? WHERE id = ?", Instant.now().minusSeconds(600), id);
        MediaAssetCleanupLease lease = lifecycle.claimCleanup(id, "worker-failure");

        lifecycle.failCleanup(lease, "provider response contained credentials");

        MediaAsset failed = assets.findById(id).orElseThrow();
        assertThat(failed.getState()).isEqualTo(MediaAssetState.CLEANUP_PENDING);
        assertThat(failed.getRetryCount()).isEqualTo(1);
        assertThat(failed.getLastErrorCode()).isEqualTo("PROVIDER_FAILURE");
        assertThat(lifecycle.claimCleanup(id, "worker-too-soon")).isNull();
        jdbc.update("UPDATE media_asset SET lease_until = ? WHERE id = ?", Instant.now().minusSeconds(600), id);
        MediaAssetCleanupLease next = lifecycle.claimCleanup(id, "worker-after-backoff");
        for (int attempt = 2; attempt <= 8; attempt++) {
            assertThat(next).as("retry %s", attempt).isNotNull();
            lifecycle.failCleanup(next, "PROVIDER_UNAVAILABLE");
            jdbc.update("UPDATE media_asset SET lease_until = ? WHERE id = ?", Instant.now().minusSeconds(10_000), id);
            next = lifecycle.claimCleanup(id, "worker-retry-" + attempt);
        }
        assertThat(next).isNull();
        assertThat(assets.findById(id).orElseThrow().getRetryCount()).isEqualTo(8);
    }

    private MediaAssetOperationLease createOperation(UUID operationId, MediaAssetIntentSpec... specs) {
        MediaAssetOperationLease operation = lifecycle.createUploadIntents(operationId, List.of(specs));
        operationLeases.put(operationId, operation);
        return operation;
    }

    private long createSingleAsset(UUID operationId, MediaKind kind, String format, long byteCount) {
        return createOperation(operationId, new MediaAssetIntentSpec(kind, format, byteCount))
                .mediaAssetIds().get(0);
    }

    private MediaUploadIntent claimSingleAsset(long assetId) {
        MediaAssetOperationLease operation = operationLeases.values().stream()
                .filter(lease -> lease.owns(assetId)).findFirst().orElseThrow();
        return lifecycle.claimUpload(operation, assetId);
    }

    private void attach(UUID operationId, List<Long> ids) {
        lifecycle.attach(operationLeases.get(operationId), ids);
    }

    private int requestCleanup(UUID operationId, List<Long> ids) {
        return lifecycle.requestCleanup(operationLeases.get(operationId), ids);
    }

    private MediaAssetState findState(long id) {
        return assets.findById(id).orElseThrow().getState();
    }

    private List<MediaAssetState> findStates(List<Long> ids) {
        return ids.stream().map(this::findState).toList();
    }

    private MediaUploadResult result(MediaUploadIntent intent) {
        String publicId = intent.publicId();
        String folder = intent.mediaKind() == MediaKind.IMAGE ? "image" : "raw";
        String format = intent.mediaKind() == MediaKind.IMAGE ? "png" : "glb";
        String suffix = intent.mediaKind() == MediaKind.IMAGE ? ".png" : "";
        return new MediaUploadResult(MediaProvider.CLOUDINARY, "test-cloud", "provider-asset-" + intent.mediaAssetId(),
                publicId, intent.mediaKind(), folder, "upload", format,  intent.mediaKind() == MediaKind.IMAGE ? 3L : 12L,
                1L, "https://res.cloudinary.com/test-cloud/" + folder + "/upload/v1/" + publicId + suffix);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StorageTestConfiguration {
        @Bean
        @Primary
        RecordingMediaStorageService recordingMediaStorageService() {
            return new RecordingMediaStorageService();
        }
    }

    static class RecordingMediaStorageService implements MediaStorageService {
        private final java.util.concurrent.CopyOnWriteArrayList<MediaAssetIdentity> deleted =
                new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public MediaUploadResult upload(MediaUploadIntent intent, ValidatedMedia media) {
            throw new UnsupportedOperationException("Lifecycle tests do not upload through the storage provider");
        }

        @Override
        public DeleteResult delete(MediaAssetIdentity identity) {
            deleted.add(identity);
            return DeleteResult.DELETED;
        }

        List<MediaAssetIdentity> deleted() {
            return List.copyOf(deleted);
        }

        void clear() {
            deleted.clear();
        }
    }
}
