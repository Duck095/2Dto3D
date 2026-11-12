package com.exe.morphia3d.service.ai;

import com.exe.morphia3d.dto.request.CreateAiGenerationRequest;
import com.exe.morphia3d.entity.AiGenerationJob;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.AiGenerationStatus;
import com.exe.morphia3d.enums.Role;
import com.exe.morphia3d.repository.AiGenerationJobRepository;
import com.exe.morphia3d.repository.UserRepository;
import com.exe.morphia3d.security.UserPrincipal;
import com.exe.morphia3d.service.MediaUploadProperties;
import com.exe.morphia3d.service.MediaValidationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiGenerationJobServiceTest {
    @Mock private AiGenerationJobRepository jobs;
    @Mock private UserRepository users;
    @Mock private AiGenerationProvider provider;
    @Mock private AiGenerationArtifactStore artifacts;

    private AiGenerationProperties properties;
    private AiGenerationJobService service;
    private UserPrincipal owner;

    @BeforeEach
    void setUp() {
        properties = new AiGenerationProperties();
        properties.setEnabled(true);
        properties.setWorkerEnabled(true);
        properties.setRequestsPerMinutePerOwner(3);
        service = new AiGenerationJobService(jobs, users, provider, artifacts, properties,
                new MediaValidationService(new MediaUploadProperties()));
        owner = new UserPrincipal(User.builder().id(41).role(Role.USER).build());
        lenient().when(provider.isConfigured()).thenReturn(true);
        lenient().when(provider.supportsIdempotency()).thenReturn(true);
        lenient().when(artifacts.isConfigured()).thenReturn(true);
        lenient().when(users.findById(41)).thenReturn(Optional.of(owner.getUser()));
    }

    @Test
    void failsClosedBeforeSavingWhenProviderOrStoreIsNotConfigured() {
        when(provider.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> service.create(owner, textRequest(), null, "request-001"))
                .isInstanceOf(AiGenerationUnavailableException.class)
                .extracting("code").isEqualTo("AI_PROVIDER_NOT_CONFIGURED");

        verify(jobs, never()).saveAndFlush(any());
        verify(artifacts, never()).storeInput(any(), any(), any());
    }

    @Test
    void rejectsImageModeWithoutImageBeforeSaving() {
        CreateAiGenerationRequest request = new CreateAiGenerationRequest("a lamp", "standard", "modern", "image");

        assertThatThrownBy(() -> service.create(owner, request, null, "request-001"))
                .isInstanceOf(AiGenerationException.class)
                .hasMessage("AI_IMAGE_REQUIRED");

        verify(jobs, never()).saveAndFlush(any());
    }

    @Test
    void acceptsTextJobWithOwnerScopedIdempotencyAndPendingStatus() {
        when(jobs.findByUser_IdAndIdempotencyKey(41, "request-001")).thenReturn(Optional.empty());
        when(jobs.saveAndFlush(any(AiGenerationJob.class))).thenAnswer(invocation -> {
            AiGenerationJob job = invocation.getArgument(0);
            job.setCreatedAt(Instant.parse("2026-11-12T00:00:00Z"));
            job.setUpdatedAt(Instant.parse("2026-11-12T00:00:00Z"));
            return job;
        });

        var response = service.create(owner, textRequest(), null, "request-001");

        ArgumentCaptor<AiGenerationJob> saved = ArgumentCaptor.forClass(AiGenerationJob.class);
        verify(jobs).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getUser().getId()).isEqualTo(41);
        assertThat(saved.getValue().getStatus()).isEqualTo(AiGenerationStatus.PENDING);
        assertThat(saved.getValue().getInputArtifactKey()).isNull();
        assertThat(saved.getValue().getRequestHash()).hasSize(64);
        assertThat(response.status()).isEqualTo(AiGenerationStatus.PENDING);
    }

    @Test
    void refusesAConflictingIdempotencyReplay() {
        AiGenerationJob prior = AiGenerationJob.builder().id(UUID.randomUUID()).user(owner.getUser())
                .idempotencyKey("request-001").requestHash("0".repeat(64)).prompt("other")
                .style("modern").quality("standard").inputMode("text").status(AiGenerationStatus.PENDING).build();
        when(jobs.findByUser_IdAndIdempotencyKey(41, "request-001")).thenReturn(Optional.of(prior));

        assertThatThrownBy(() -> service.create(owner, textRequest(), null, "request-001"))
                .isInstanceOf(AiGenerationException.class)
                .hasMessage("AI_IDEMPOTENCY_CONFLICT");

        verify(jobs, never()).saveAndFlush(any());
    }

    @Test
    void sameOwnerSamePayloadReplayReturnsPriorJobWithoutSavingAnother() {
        UUID existingId = UUID.randomUUID();
        when(jobs.findByUser_IdAndIdempotencyKey(41, "request-001")).thenReturn(Optional.empty());
        when(jobs.saveAndFlush(any(AiGenerationJob.class))).thenAnswer(invocation -> {
            AiGenerationJob created = invocation.getArgument(0);
            created.setCreatedAt(Instant.parse("2026-11-12T00:00:00Z"));
            created.setUpdatedAt(Instant.parse("2026-11-12T00:00:00Z"));
            return created;
        });
        var first = service.create(owner, textRequest(), null, "request-001");
        ArgumentCaptor<AiGenerationJob> created = ArgumentCaptor.forClass(AiGenerationJob.class);
        verify(jobs).saveAndFlush(created.capture());
        AiGenerationJob prior = AiGenerationJob.builder().id(existingId).user(owner.getUser())
                .idempotencyKey("request-001").requestHash("unused").prompt("a lamp")
                .style("modern").quality("standard").inputMode("text").status(AiGenerationStatus.PENDING).build();
        prior.setRequestHash(created.getValue().getRequestHash());
        when(jobs.findByUser_IdAndIdempotencyKey(41, "request-001")).thenReturn(Optional.of(prior));

        var replay = service.create(owner, textRequest(), null, "request-001");

        assertThat(replay.id()).isEqualTo(existingId);
        assertThat(replay.status()).isEqualTo(AiGenerationStatus.PENDING);
        assertThat(first.id()).isNotEqualTo(replay.id());
        verify(jobs, org.mockito.Mockito.times(1)).saveAndFlush(any(AiGenerationJob.class));
    }

    @Test
    void appliesPerOwnerRequestRateLimit() {
        when(jobs.findByUser_IdAndIdempotencyKey(org.mockito.ArgumentMatchers.eq(41), any()))
                .thenReturn(Optional.empty());
        when(jobs.saveAndFlush(any(AiGenerationJob.class))).thenAnswer(invocation -> {
            AiGenerationJob job = invocation.getArgument(0);
            job.setCreatedAt(Instant.now());
            job.setUpdatedAt(Instant.now());
            return job;
        });

        service.create(owner, textRequest(), null, "request-001");
        service.create(owner, textRequest(), null, "request-002");
        service.create(owner, textRequest(), null, "request-003");

        assertThatThrownBy(() -> service.create(owner, textRequest(), null, "request-004"))
                .isInstanceOf(AiGenerationException.class)
                .hasMessage("AI_RATE_LIMITED");
    }

    private CreateAiGenerationRequest textRequest() {
        return new CreateAiGenerationRequest("a lamp", "standard", "modern", "text");
    }

    private MockMultipartFile validGlb() {
        byte[] bytes = new byte[12];
        bytes[0] = 'g'; bytes[1] = 'l'; bytes[2] = 'T'; bytes[3] = 'F';
        bytes[4] = 2; bytes[8] = 12;
        return new MockMultipartFile("image", "input.glb", "model/gltf-binary", bytes);
    }
}
