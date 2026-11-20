package com.exe.morphia3d.service;

import com.exe.morphia3d.service.MediaStorageException.Reason;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** Database leases coordinate workers; this scheduler is only a poller and is not an ownership lock. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "media.lifecycle",
        name = "reconciliation-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class MediaAssetReconciler {

    private static final Logger log = LoggerFactory.getLogger(MediaAssetReconciler.class);
    private final MediaAssetLifecycleService lifecycle;
    private final MediaStorageService storage;

    @Scheduled(fixedDelayString = "${media.lifecycle.reconciliation-interval-millis:30000}")
    public void scheduledReconcile() {
        reconcileOnce();
    }

    public int reconcileOnce() {
        try {
            lifecycle.recoverStaleUploadIntents();
            lifecycle.recoverExpiredCleanupLeases();
        } catch (RuntimeException exception) {
            // A failed database check must not be followed by a provider deletion.
            log.warn("Media reconciliation preflight failed; cleanup deferred");
            return 0;
        }

        int completed = 0;
        List<Long> candidates;
        try {
            candidates = lifecycle.cleanupCandidateIds();
        } catch (RuntimeException exception) {
            log.warn("Media cleanup scan failed; cleanup deferred");
            return 0;
        }

        for (Long id : candidates) {
            MediaAssetCleanupLease lease = null;
            try {
                lease = lifecycle.claimCleanup(id, workerId());
                if (lease == null) {
                    continue;
                }
                // This is a separate, fresh committed read. Never make a provider call on DB uncertainty.
                MediaAssetIdentity identity = lifecycle.verifyCleanupLease(lease);
                DeleteResult result = storage.delete(identity);
                lifecycle.finishCleanup(lease, result);
                completed++;
            } catch (RuntimeException failure) {
                String errorCode = safeFailureCode(failure);
                if (lease != null) {
                    try {
                        lifecycle.failCleanup(lease, errorCode);
                    } catch (RuntimeException persistenceFailure) {
                        // Keep the lease for expiry recovery if the database cannot record this attempt.
                        log.warn("Media cleanup result could not be persisted; lease will expire (assetId={}, code={})",
                                id, errorCode);
                    }
                }
                log.warn("Media cleanup attempt deferred (assetId={}, code={})", id, errorCode);
            }
        }
        return completed;
    }

    private String workerId() {
        return Thread.currentThread().getName() + ":" + UUID.randomUUID();
    }

    private String safeFailureCode(RuntimeException failure) {
        if (failure instanceof MediaStorageException mediaFailure) {
            Reason reason = mediaFailure.getReason();
            return reason.name();
        }
        if (failure instanceof IllegalStateException) {
            return "LEASE_OR_REFERENCE_CHECK_FAILED";
        }
        return "PROVIDER_FAILURE";
    }
}
