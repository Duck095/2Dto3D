package com.exe.morphia3d.service;

import com.exe.morphia3d.enums.MediaKind;
import com.exe.morphia3d.enums.MediaProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaAssetReconcilerTest {

    @Mock
    private MediaAssetLifecycleService lifecycle;

    @Mock
    private MediaStorageService storage;

    @Test
    void doesNotDeleteWhenFreshDatabaseVerificationFails() {
        MediaAssetCleanupLease lease = lease();
        when(lifecycle.cleanupCandidateIds()).thenReturn(List.of(lease.mediaAssetId()));
        when(lifecycle.claimCleanup(eq(lease.mediaAssetId()), any())).thenReturn(lease);
        when(lifecycle.verifyCleanupLease(lease)).thenThrow(new IllegalStateException("database unavailable"));
        MediaAssetReconciler reconciler = new MediaAssetReconciler(lifecycle, storage);

        assertThat(reconciler.reconcileOnce()).isZero();

        verify(storage, never()).delete(any());
        verify(lifecycle).failCleanup(lease, "LEASE_OR_REFERENCE_CHECK_FAILED");
    }

    @Test
    void retriesAnAmbiguousSuccessfulDeleteAndAcceptsProviderNotFound() {
        MediaAssetCleanupLease lease = lease();
        MediaAssetIdentity identity = identity();
        when(lifecycle.cleanupCandidateIds()).thenReturn(List.of(lease.mediaAssetId()));
        when(lifecycle.claimCleanup(eq(lease.mediaAssetId()), any())).thenReturn(lease);
        when(lifecycle.verifyCleanupLease(lease)).thenReturn(identity);
        when(storage.delete(identity)).thenReturn(DeleteResult.DELETED, DeleteResult.NOT_FOUND);
        doThrow(new IllegalStateException("commit outcome unknown"))
                .doNothing().when(lifecycle).finishCleanup(lease, DeleteResult.DELETED);
        MediaAssetReconciler reconciler = new MediaAssetReconciler(lifecycle, storage);

        assertThat(reconciler.reconcileOnce()).isZero();
        when(storage.delete(identity)).thenReturn(DeleteResult.NOT_FOUND);
        when(lifecycle.cleanupCandidateIds()).thenReturn(List.of(lease.mediaAssetId()));
        assertThat(reconciler.reconcileOnce()).isEqualTo(1);

        verify(storage, org.mockito.Mockito.times(2)).delete(identity);
        verify(lifecycle).finishCleanup(lease, DeleteResult.NOT_FOUND);
    }

    @Test
    void recordsSanitizedProviderFailureForBoundedRetry() {
        MediaAssetCleanupLease lease = lease();
        MediaAssetIdentity identity = identity();
        when(lifecycle.cleanupCandidateIds()).thenReturn(List.of(lease.mediaAssetId()));
        when(lifecycle.claimCleanup(eq(lease.mediaAssetId()), any())).thenReturn(lease);
        when(lifecycle.verifyCleanupLease(lease)).thenReturn(identity);
        when(storage.delete(identity)).thenThrow(new MediaStorageException(
                MediaStorageException.Reason.PROVIDER_UNAVAILABLE));
        MediaAssetReconciler reconciler = new MediaAssetReconciler(lifecycle, storage);

        assertThat(reconciler.reconcileOnce()).isZero();

        verify(lifecycle).failCleanup(lease, "PROVIDER_UNAVAILABLE");
    }

    @Test
    void databaseUnavailableBeforeClaimNeverCallsProvider() {
        when(lifecycle.cleanupCandidateIds()).thenThrow(new IllegalStateException("db down"));
        MediaAssetReconciler reconciler = new MediaAssetReconciler(lifecycle, storage);

        assertThat(reconciler.reconcileOnce()).isZero();

        verify(storage, never()).delete(any());
    }

    private MediaAssetCleanupLease lease() {
        return new MediaAssetCleanupLease(10L, UUID.fromString("11111111-1111-4111-8111-111111111111"),
                UUID.fromString("22222222-2222-4222-8222-222222222222"));
    }

    private MediaAssetIdentity identity() {
        return new MediaAssetIdentity(MediaProvider.CLOUDINARY, "test-cloud", null,
                "product-media/images/11111111-1111-4111-8111-111111111111/10", MediaKind.IMAGE, "image", "upload");
    }
}
