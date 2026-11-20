package com.exe.morphia3d.service;

import com.exe.morphia3d.enums.MediaKind;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class MediaUploadHeartbeatTest {

    @Test
    void refreshesLeaseWhileProviderCallIsStillRunning() {
        MediaAssetLifecycleService lifecycle = mock(MediaAssetLifecycleService.class);
        MediaLifecycleProperties properties = new MediaLifecycleProperties();
        properties.setUploadLeaseMillis(150);
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("media-heartbeat-test-");
        scheduler.initialize();
        try {
            MediaUploadIntent intent = intent();
            CountDownLatch refreshes = new CountDownLatch(2);
            AtomicInteger refreshCount = new AtomicInteger();
            doAnswer(invocation -> {
                refreshCount.incrementAndGet();
                refreshes.countDown();
                return null;
            }).when(lifecycle).refreshUploadLease(intent);
            MediaUploadHeartbeat heartbeat = new MediaUploadHeartbeat(lifecycle, scheduler, properties);

            String result = heartbeat.during(intent, () -> {
                try {
                    assertThat(refreshes.await(2, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return "uploaded";
            });

            assertThat(result).isEqualTo("uploaded");
            assertThat(refreshCount.get()).isGreaterThanOrEqualTo(2);
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void staleLeasePreventsProviderCallAndCannotBeRenewedByCallback() {
        MediaAssetLifecycleService lifecycle = mock(MediaAssetLifecycleService.class);
        MediaLifecycleProperties properties = new MediaLifecycleProperties();
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.initialize();
        try {
            MediaUploadIntent intent = intent();
            org.mockito.Mockito.doThrow(new IllegalStateException("lease expired"))
                    .when(lifecycle).refreshUploadLease(intent);
            MediaUploadHeartbeat heartbeat = new MediaUploadHeartbeat(lifecycle, scheduler, properties);
            AtomicInteger providerCalls = new AtomicInteger();

            assertThatThrownBy(() -> heartbeat.during(intent, () -> {
                providerCalls.incrementAndGet();
                return "should not upload";
            })).isInstanceOf(IllegalStateException.class);

            assertThat(providerCalls.get()).isZero();
            verify(lifecycle, never()).recordUpload(org.mockito.ArgumentMatchers.eq(intent),
                    org.mockito.ArgumentMatchers.any());
        } finally {
            scheduler.shutdown();
        }
    }

    private MediaUploadIntent intent() {
        return new MediaUploadIntent(1, UUID.randomUUID(), UUID.randomUUID(), MediaKind.IMAGE);
    }
}
