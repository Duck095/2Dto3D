package com.exe.morphia3d.service.admin3d;

import com.exe.morphia3d.dto.response.AdminProductPrintAssetResponse;
import com.exe.morphia3d.entity.Model3d;
import com.exe.morphia3d.enums.MediaKind;
import com.exe.morphia3d.enums.ModelType;
import com.exe.morphia3d.enums.Role;
import com.exe.morphia3d.repository.Model3dRepository;
import com.exe.morphia3d.security.UserPrincipal;
import com.exe.morphia3d.service.MediaAssetIntentSpec;
import com.exe.morphia3d.service.MediaAssetLifecycleService;
import com.exe.morphia3d.service.MediaAssetOperationLease;
import com.exe.morphia3d.service.MediaStorageService;
import com.exe.morphia3d.service.MediaUploadHeartbeat;
import com.exe.morphia3d.service.MediaUploadIntent;
import com.exe.morphia3d.service.MediaUploadLeaseException;
import com.exe.morphia3d.service.MediaUploadResult;
import com.exe.morphia3d.service.MediaValidationService;
import com.exe.morphia3d.service.ValidatedMedia;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProductPrintAssetUploadService {

    private final Model3dRepository products;
    private final MediaValidationService validation;
    private final MediaStorageService storage;
    private final MediaAssetLifecycleService lifecycle;
    private final MediaUploadHeartbeat heartbeat;
    private final ProductPrintAssetPersistenceService persistence;

    public AdminProductPrintAssetResponse upload(UserPrincipal principal, Integer productId, MultipartFile file) {
        requireAdmin(principal);
        requireAdminProduct(productId);
        ValidatedMedia media = validation.validate3mf(file);
        String fileName = originalFileName(file.getOriginalFilename());

        MediaAssetOperationLease operation = lifecycle.createUploadIntents(UUID.randomUUID(),
                List.of(new MediaAssetIntentSpec(MediaKind.PRINT_3MF, media.format(), media.byteCount())));
        try {
            Long mediaAssetId = operation.mediaAssetIds().get(0);
            MediaUploadIntent intent = lifecycle.claimUpload(operation, mediaAssetId);
            if (intent == null) {
                throw new MediaUploadLeaseException("Print file upload lease is no longer active");
            }
            MediaUploadResult uploaded = heartbeat.during(intent, () -> storage.upload(intent, media));
            lifecycle.recordUpload(intent, uploaded);
            return persistence.attach(productId, principal.getId(), fileName, operation, mediaAssetId);
        } catch (RuntimeException failure) {
            try {
                lifecycle.requestCleanup(operation, operation.mediaAssetIds());
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    public List<AdminProductPrintAssetResponse> list(UserPrincipal principal, Integer productId) {
        requireAdmin(principal);
        return persistence.list(productId);
    }

    private void requireAdmin(UserPrincipal principal) {
        if (principal == null || principal.getId() == null || principal.getUser().getRole() != Role.ADMIN) {
            throw new AccessDeniedException("Administrator identity is required");
        }
    }

    private void requireAdminProduct(Integer productId) {
        Model3d product = products.findById(productId)
                .orElseThrow(() -> new EntityNotFoundException("Admin product not found"));
        if (product.getSourceType() != ModelType.DESIGNER_MODEL) {
            throw new EntityNotFoundException("Admin product not found");
        }
    }

    private String originalFileName(String supplied) {
        if (supplied == null || supplied.isBlank()) {
            throw new IllegalArgumentException("Print file name is required");
        }
        String fileName = Paths.get(supplied.replace('\\', '/')).getFileName().toString().trim();
        if (fileName.isBlank() || fileName.length() > 255 || fileName.contains("\u0000")) {
            throw new IllegalArgumentException("Print file name must contain 1 to 255 characters");
        }
        return fileName;
    }
}
