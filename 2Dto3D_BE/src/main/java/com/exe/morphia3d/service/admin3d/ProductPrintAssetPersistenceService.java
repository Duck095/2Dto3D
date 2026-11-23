package com.exe.morphia3d.service.admin3d;

import com.exe.morphia3d.dto.response.AdminProductPrintAssetResponse;
import com.exe.morphia3d.entity.MediaAsset;
import com.exe.morphia3d.entity.Model3d;
import com.exe.morphia3d.entity.ProductPrintAsset;
import com.exe.morphia3d.entity.ProductPrintAssetId;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.MediaAssetState;
import com.exe.morphia3d.enums.MediaKind;
import com.exe.morphia3d.enums.ModelType;
import com.exe.morphia3d.repository.MediaAssetRepository;
import com.exe.morphia3d.repository.Model3dRepository;
import com.exe.morphia3d.repository.ProductPrintAssetRepository;
import com.exe.morphia3d.repository.UserRepository;
import com.exe.morphia3d.service.MediaAssetLifecycleService;
import com.exe.morphia3d.service.MediaAssetOperationLease;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductPrintAssetPersistenceService {

    private final Model3dRepository products;
    private final MediaAssetRepository mediaAssets;
    private final ProductPrintAssetRepository printAssets;
    private final UserRepository users;
    private final MediaAssetLifecycleService lifecycle;
    private final Clock clock;

    @Transactional
    public AdminProductPrintAssetResponse attach(Integer productId, Integer adminId, String fileName,
                                                MediaAssetOperationLease operation, Long mediaAssetId) {
        Model3d product = requireAdminProduct(productId);
        User admin = users.findById(adminId).orElseThrow(() -> new EntityNotFoundException("Admin user not found"));
        MediaAsset mediaAsset = mediaAssets.findById(mediaAssetId)
                .orElseThrow(() -> new IllegalStateException("Print media asset is missing"));
        if (mediaAsset.getMediaKind() != MediaKind.PRINT_3MF || mediaAsset.getState() != MediaAssetState.READY
                || !operation.owns(mediaAssetId)) {
            throw new IllegalStateException("Only this operation's READY 3MF asset can be attached");
        }

        ProductPrintAsset link = new ProductPrintAsset();
        link.setId(new ProductPrintAssetId(productId, mediaAssetId));
        link.setProduct(product);
        link.setMediaAsset(mediaAsset);
        link.setOriginalFileName(fileName);
        link.setUploadedBy(admin);
        link.setUploadedAt(Instant.now(clock));
        printAssets.saveAndFlush(link);
        lifecycle.attach(operation, List.of(mediaAssetId));
        return response(link, mediaAsset, MediaAssetState.ATTACHED);
    }

    @Transactional(readOnly = true)
    public List<AdminProductPrintAssetResponse> list(Integer productId) {
        requireAdminProduct(productId);
        return printAssets.findAllByIdProductIdOrderByUploadedAtDescIdMediaAssetIdDesc(productId).stream()
                .map(link -> response(link, link.getMediaAsset(), link.getMediaAsset().getState()))
                .toList();
    }

    private Model3d requireAdminProduct(Integer productId) {
        Model3d product = products.findById(productId)
                .orElseThrow(() -> new EntityNotFoundException("Admin product not found"));
        if (product.getSourceType() != ModelType.DESIGNER_MODEL) {
            throw new EntityNotFoundException("Admin product not found");
        }
        return product;
    }

    private AdminProductPrintAssetResponse response(ProductPrintAsset link, MediaAsset mediaAsset,
                                                   MediaAssetState status) {
        return new AdminProductPrintAssetResponse(mediaAsset.getId(), link.getOriginalFileName(),
                mediaAsset.getByteCount(), status, link.getUploadedAt());
    }
}
