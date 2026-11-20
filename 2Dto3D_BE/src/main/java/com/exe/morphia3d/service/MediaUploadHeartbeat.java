package com.exe.morphia3d.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Renews the durable operation lease while a blocking provider request is in progress. */
@Component
@RequiredArgsConstructor
public class MediaUploadHeartbeat {

    private final MediaAssetLifecycleService lifecycle;
    private final TaskScheduler scheduler;
    private final MediaLifecycleProperties properties;

    public <T> T during(MediaUploadIntent intent, Supplier<T> providerCall) {
        lifecycle.refreshUploadLease(intent);
        long intervalMillis = Math.max(1, properties.getUploadLeaseMillis() / 3);
        LeaseHeartbeat scope = new LeaseHeartbeat(intent);
        scope.schedule(intervalMillis);

        T result;
        try {
            result = providerCall.get();
        } catch (RuntimeException failure) {
            scope.close();
            throw failure;
        } catch (Error failure) {
            scope.close();
            throw failure;
        }
        scope.close();
        scope.throwIfRenewalFailed();
        return result;
    }

    private final class LeaseHeartbeat implements AutoCloseable {
        private final MediaUploadIntent intent;
        private final Object gate = new Object();
        private final AtomicReference<RuntimeException> renewalFailure = new AtomicReference<>();
        private volatile boolean closed;
        private ScheduledFuture<?> future;

        private LeaseHeartbeat(MediaUploadIntent intent) {
            this.intent = intent;
        }

        private void schedule(long intervalMillis) {
            future = scheduler.scheduleAtFixedRate(this::refresh, Duration.ofMillis(intervalMillis));
        }

        private void refresh() {
            synchronized (gate) {
                if (closed || renewalFailure.get() != null) {
                    return;
                }
                try {
                    lifecycle.refreshUploadLease(intent);
                } catch (RuntimeException failure) {
                    renewalFailure.compareAndSet(null, failure);
                    ScheduledFuture<?> scheduled = future;
                    if (scheduled != null) {
                        scheduled.cancel(false);
                    }
                }
            }
        }

        @Override
        public void close() {
            ScheduledFuture<?> scheduled = future;
            if (scheduled != null) {
                scheduled.cancel(false);
            }
            synchronized (gate) {
                closed = true;
            }
        }

        private void throwIfRenewalFailed() {
            RuntimeException failure = renewalFailure.get();
            if (failure != null) {
                throw new MediaUploadLeaseException("Media upload lease renewal failed", failure);
            }
        }
    }
}
