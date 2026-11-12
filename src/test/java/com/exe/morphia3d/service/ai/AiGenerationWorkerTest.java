package com.exe.morphia3d.service.ai;

import com.exe.morphia3d.entity.AiGenerationJob;
import com.exe.morphia3d.enums.AiGenerationStatus;
import com.exe.morphia3d.repository.AiGenerationJobRepository;
import com.exe.morphia3d.service.MediaUploadProperties;
import com.exe.morphia3d.service.MediaValidationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiGenerationWorkerTest {
    @Mock private AiGenerationJobRepository jobs;
    @Mock private AiGenerationProvider provider;
    @Mock private AiGenerationArtifactStore artifacts;

    private AiGenerationProperties properties;
    private AiGenerationWorker worker;

    @BeforeEach
    void setUp() {
        properties = new AiGenerationProperties();
        properties.setEnabled(true);
        properties.setWorkerEnabled(true);
        lenient().when(provider.isConfigured()).thenReturn(true);
        lenient().when(provider.supportsIdempotency()).thenReturn(true);
        lenient().when(artifacts.isConfigured()).thenReturn(true);
        worker = new AiGenerationWorker(jobs, provider, artifacts, properties,
                new MediaValidationService(new MediaUploadProperties()));
    }

    @Test
    void doesNotClaimWhenWorkerFlagIsOff() {
        properties.setWorkerEnabled(false);

        assertThat(worker.processOne()).isFalse();

        verify(jobs, never()).claimNextPending(anyString(), any(), any(), any(Integer.class));
    }

    @Test
    void validatesAndPersistsProviderGlbBeforeCompletingJob() {
        AiGenerationJob job = processingJob();
        when(jobs.claimNextPending(anyString(), any(), any(), any(Integer.class))).thenAnswer(invocation -> {
            job.setProcessingLeaseOwner(invocation.getArgument(0));
            return 1;
        });
        when(jobs.findFirstByProcessingLeaseOwnerAndStatus(anyString(), org.mockito.ArgumentMatchers.eq(AiGenerationStatus.PROCESSING)))
                .thenReturn(Optional.of(job));
        when(provider.generate(any(AiGenerationInput.class), org.mockito.ArgumentMatchers.eq(job.getId())))
                .thenReturn(new AiGenerationOutput(validGlb(), "lamp"));
        when(artifacts.storeResult(org.mockito.ArgumentMatchers.eq(job.getId()), any()))
                .thenReturn(new StoredArtifact("result/" + job.getId() + ".glb", "model/gltf-binary", validGlb().length));
        when(jobs.findByIdAndProcessingLeaseOwner(org.mockito.ArgumentMatchers.eq(job.getId()), anyString()))
                .thenReturn(Optional.of(job));
        when(jobs.saveAndFlush(job)).thenReturn(job);

        assertThat(worker.processOne()).isTrue();

        assertThat(job.getStatus()).isEqualTo(AiGenerationStatus.COMPLETED);
        assertThat(job.getResultArtifactKey()).isEqualTo("result/" + job.getId() + ".glb");
        verify(jobs).saveAndFlush(job);
    }

    @Test
    void malformedProviderOutputFailsClosedWithoutStoringResult() {
        AiGenerationJob job = processingJob();
        when(jobs.claimNextPending(anyString(), any(), any(), any(Integer.class))).thenAnswer(invocation -> {
            job.setProcessingLeaseOwner(invocation.getArgument(0));
            return 1;
        });
        when(jobs.findFirstByProcessingLeaseOwnerAndStatus(anyString(), org.mockito.ArgumentMatchers.eq(AiGenerationStatus.PROCESSING)))
                .thenReturn(Optional.of(job));
        when(provider.generate(any(AiGenerationInput.class), org.mockito.ArgumentMatchers.eq(job.getId())))
                .thenReturn(new AiGenerationOutput(new byte[]{1, 2, 3}, "bad"));
        when(jobs.findByIdAndProcessingLeaseOwner(org.mockito.ArgumentMatchers.eq(job.getId()), anyString()))
                .thenReturn(Optional.of(job));
        when(jobs.saveAndFlush(job)).thenReturn(job);

        assertThat(worker.processOne()).isTrue();

        assertThat(job.getStatus()).isEqualTo(AiGenerationStatus.FAILED);
        assertThat(job.getErrorCode()).isEqualTo("AI_INVALID_PROVIDER_OUTPUT");
        verify(artifacts, never()).storeResult(any(), any());
    }

    private AiGenerationJob processingJob() {
        return AiGenerationJob.builder().id(UUID.randomUUID()).idempotencyKey("test-key").requestHash("a".repeat(64))
                .prompt("lamp").style("modern").quality("standard").inputMode("text")
                .status(AiGenerationStatus.PROCESSING).attemptCount(1).processingLeaseOwner("worker-test").build();
    }

    private byte[] validGlb() {
        byte[] bytes = new byte[12];
        bytes[0] = 'g'; bytes[1] = 'l'; bytes[2] = 'T'; bytes[3] = 'F';
        bytes[4] = 2; bytes[8] = 12;
        return bytes;
    }
}
