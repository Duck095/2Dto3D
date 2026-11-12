package com.exe.morphia3d.controller;

import com.exe.morphia3d.dto.request.CreateAiGenerationRequest;
import com.exe.morphia3d.dto.response.AiGenerationJobResponse;
import com.exe.morphia3d.feature.FeatureFamily;
import com.exe.morphia3d.feature.FeatureGate;
import com.exe.morphia3d.enums.Role;
import com.exe.morphia3d.security.UserPrincipal;
import com.exe.morphia3d.service.ai.AiGenerationJobService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/ai-studio/generations")
@RequiredArgsConstructor
public class AiGenerationController {
    private final AiGenerationJobService jobs;
    private final FeatureGate featureGate;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AiGenerationJobResponse> create(
            @Valid @RequestPart("data") CreateAiGenerationRequest request,
            @RequestPart(value = "image", required = false) MultipartFile image,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal UserPrincipal principal) {
        requireAllowed(principal);
        featureGate.requireEnabled(FeatureFamily.AI);
        return ResponseEntity.accepted().body(jobs.create(principal, request, image, idempotencyKey));
    }

    @GetMapping("/history")
    public Page<AiGenerationJobResponse> history(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @AuthenticationPrincipal UserPrincipal principal) {
        requireAllowed(principal);
        featureGate.requireEnabled(FeatureFamily.AI);
        return jobs.history(principal.getId(), PageRequest.of(page, size));
    }

    @GetMapping("/{id}")
    public AiGenerationJobResponse get(@PathVariable UUID id, @AuthenticationPrincipal UserPrincipal principal) {
        requireAllowed(principal);
        featureGate.requireEnabled(FeatureFamily.AI);
        return jobs.findForOwner(id, principal.getId());
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<AiGenerationJobResponse> retry(
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal UserPrincipal principal) {
        requireAllowed(principal);
        featureGate.requireEnabled(FeatureFamily.AI);
        return ResponseEntity.accepted().body(jobs.retry(id, principal, idempotencyKey));
    }

    @GetMapping(value = "/{id}/result", produces = "model/gltf-binary")
    public ResponseEntity<InputStreamResource> result(@PathVariable UUID id,
                                                       @AuthenticationPrincipal UserPrincipal principal) {
        requireAllowed(principal);
        featureGate.requireEnabled(FeatureFamily.AI);
        InputStreamResource body = new InputStreamResource(jobs.openResultForOwner(id, principal.getId()));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("model/gltf-binary"))
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"generation-" + id + ".glb\"")
                .body(body);
    }

    private void requireAllowed(UserPrincipal principal) {
        if (principal == null || principal.getUser() == null
                || (principal.getUser().getRole() != Role.USER && principal.getUser().getRole() != Role.ADMIN)) {
            throw new AccessDeniedException("AI generation is limited to customer and administrator accounts.");
        }
    }
}
