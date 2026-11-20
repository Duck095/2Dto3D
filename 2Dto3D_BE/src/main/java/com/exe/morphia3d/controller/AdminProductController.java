package com.exe.morphia3d.controller;

import com.exe.morphia3d.dto.request.AdminProductUpdateRequest;
import com.exe.morphia3d.dto.request.DesignerModelRequest;
import com.exe.morphia3d.dto.response.AdminProductPageResponse;
import com.exe.morphia3d.dto.response.ModelResponse;
import com.exe.morphia3d.enums.ModelStatus;
import com.exe.morphia3d.feature.FeatureGate;
import com.exe.morphia3d.security.UserPrincipal;
import com.exe.morphia3d.service.AdminProductCatalogService;
import com.exe.morphia3d.service.DesignerModelUploadService;
import com.exe.morphia3d.service.Model3dService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/products")
public class AdminProductController {
    private final AdminProductCatalogService catalog;
    private final DesignerModelUploadService upload;
    private final Model3dService publication;
    private final FeatureGate featureGate;

    @GetMapping
    public ResponseEntity<AdminProductPageResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) ModelStatus status) {
        return ResponseEntity.ok(catalog.list(page, size, q, status));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ModelResponse> get(@PathVariable Integer id) {
        return ResponseEntity.ok(catalog.get(id));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ModelResponse> create(
            Authentication authentication,
            @RequestPart("data") DesignerModelRequest request,
            @RequestPart("images") List<MultipartFile> images,
            @RequestPart("model") MultipartFile model) {
        UserPrincipal principal = currentPrincipal(authentication);
        ModelResponse response = featureGate.isAdminProductSnapshotUploadEnabled()
                ? upload.createWithSnapshot(principal, request, images, model)
                : upload.create(principal, request, images, model);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ModelResponse> update(@PathVariable Integer id,
                                                 @RequestBody AdminProductUpdateRequest request) {
        return ResponseEntity.ok(catalog.update(id, request));
    }

    @PostMapping("/{id}/publish")
    public ResponseEntity<Void> publish(@PathVariable Integer id) {
        publication.publishDesignerModel(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/archive")
    public ResponseEntity<Void> archive(@PathVariable Integer id) {
        catalog.archive(id);
        return ResponseEntity.noContent().build();
    }

    private UserPrincipal currentPrincipal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal)
                || principal.getId() == null) {
            throw new AccessDeniedException("Authenticated user principal is required");
        }
        return principal;
    }
}
