package com.exe.morphia3d.controller;

import com.exe.morphia3d.dto.response.AdminProductPrintAssetResponse;
import com.exe.morphia3d.security.UserPrincipal;
import com.exe.morphia3d.service.admin3d.ProductPrintAssetUploadService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/products/{id}/print-assets")
public class AdminProductPrintAssetController {

    private final ProductPrintAssetUploadService printAssets;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AdminProductPrintAssetResponse> upload(
            Authentication authentication,
            @PathVariable Integer id,
            @RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(printAssets.upload(currentAdmin(authentication), id, file));
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<AdminProductPrintAssetResponse>> list(
            Authentication authentication,
            @PathVariable Integer id) {
        return ResponseEntity.ok(printAssets.list(currentAdmin(authentication), id));
    }

    private UserPrincipal currentAdmin(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal)
                || principal.getId() == null) {
            throw new AccessDeniedException("Authenticated administrator identity is required");
        }
        return principal;
    }
}
