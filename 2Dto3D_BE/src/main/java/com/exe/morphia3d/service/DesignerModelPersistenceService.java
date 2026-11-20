package com.exe.morphia3d.service;

import com.exe.morphia3d.dto.request.DesignerModelRequest;
import com.exe.morphia3d.dto.request.ModelPartRequest;
import com.exe.morphia3d.dto.response.ModelResponse;
import com.exe.morphia3d.entity.Color;
import com.exe.morphia3d.entity.MediaAsset;
import com.exe.morphia3d.entity.Model3d;
import com.exe.morphia3d.entity.ModelImage;
import com.exe.morphia3d.entity.ModelPart;
import com.exe.morphia3d.entity.ProductGlbStructureSnapshot;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.MediaAssetState;
import com.exe.morphia3d.enums.MediaKind;
import com.exe.morphia3d.enums.ModelType;
import com.exe.morphia3d.mapper.ModelMapper;
import com.exe.morphia3d.repository.ColorRepository;
import com.exe.morphia3d.repository.MediaAssetRepository;
import com.exe.morphia3d.repository.Model3dRepository;
import com.exe.morphia3d.repository.ProductGlbStructureSnapshotRepository;
import com.exe.morphia3d.repository.UserRepository;
import com.exe.morphia3d.security.UserPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns the separate atomic database transaction that attaches an uploaded media operation to a model. */
@Service
@RequiredArgsConstructor
public class DesignerModelPersistenceService {

    private final PlatformTransactionManager transactionManager;
    private final MediaAssetRepository assets;
    private final Model3dRepository models;
    private final UserRepository users;
    private final ColorRepository colors;
    private final ModelMapper mapper;
    private final MediaAssetLifecycleService lifecycle;
    private final ProductGlbStructureSnapshotRepository structureSnapshots;
    private final EntityManager entityManager;

    public ModelResponse persist(UserPrincipal principal,
                                 DesignerModelRequest metadata,
                                 MediaAssetOperationLease operation) {
        return persistInTransaction(principal, metadata, operation, null);
    }

    /** Persists a trusted parser snapshot atomically with the new product and attached media. */
    public ModelResponse persistWithSnapshot(UserPrincipal principal,
                                             DesignerModelRequest metadata,
                                             MediaAssetOperationLease operation,
                                             String parserVersion,
                                             JsonNode structureJson) {
        return persistInTransaction(principal, metadata, operation,
                new SnapshotPayload(parserVersion, structureJson));
    }

    private ModelResponse persistInTransaction(UserPrincipal principal,
                                               DesignerModelRequest metadata,
                                               MediaAssetOperationLease operation,
                                               SnapshotPayload snapshot) {
        AtomicBoolean callbackCompleted = new AtomicBoolean(false);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        try {
            ModelResponse response = transaction.execute(status -> {
                ModelResponse saved = persistWithinTransaction(principal, metadata, operation, snapshot);
                callbackCompleted.set(true);
                return saved;
            });
            if (response == null) {
                throw new IllegalStateException("Model persistence transaction returned no response");
            }
            return response;
        } catch (RuntimeException failure) {
            if (callbackCompleted.get() || failure instanceof TransactionSystemException) {
                throw MediaPersistenceException.outcomeUnknown(failure);
            }
            throw MediaPersistenceException.rolledBack(failure);
        }
    }

    private ModelResponse persistWithinTransaction(UserPrincipal principal,
                                                   DesignerModelRequest metadata,
                                                   MediaAssetOperationLease operation,
                                                   SnapshotPayload snapshot) {
        if (snapshot != null) {
            snapshot = new SnapshotPayload(snapshot.parserVersion(),
                    ProductGlbStructureSnapshot.validatedStructureDocument(
                            snapshot.parserVersion(), snapshot.structureJson()));
        }
        if (principal == null || principal.getId() == null || metadata == null || operation == null
                || operation.mediaAssetIds().size() < 2) {
            throw new IllegalArgumentException("A valid principal, model request, and complete media operation are required");
        }
        List<MediaAsset> operationAssets = assets.findAllById(operation.mediaAssetIds());
        if (operationAssets.size() != operation.mediaAssetIds().size()) {
            throw new IllegalStateException("The upload operation contains missing media assets");
        }
        Map<Long, MediaAsset> byId = new HashMap<>();
        for (MediaAsset asset : operationAssets) {
            if (!operation.operationId().equals(asset.getOperationId())
                    || asset.getState() != MediaAssetState.READY) {
                throw new IllegalStateException("The upload operation does not own READY media assets");
            }
            byId.put(asset.getId(), asset);
        }

        MediaAsset modelAsset = byId.get(operation.mediaAssetIds().get(0));
        if (modelAsset == null || modelAsset.getMediaKind() != MediaKind.MODEL_3D) {
            throw new IllegalStateException("The first operation asset must be its GLB model");
        }
        List<Long> imageAssetIds = operation.mediaAssetIds().subList(1, operation.mediaAssetIds().size());
        List<MediaAsset> imageAssets = imageAssetIds.stream().map(byId::get).toList();
        if (imageAssets.stream().anyMatch(asset -> asset == null || asset.getMediaKind() != MediaKind.IMAGE)) {
            throw new IllegalStateException("All remaining operation assets must be product images");
        }

        User owner = users.findById(principal.getId())
                .orElseThrow(() -> new IllegalStateException("Authenticated designer account is unavailable"));
        Model3d model = Model3d.builder()
                .sourceType(ModelType.DESIGNER_MODEL)
                .user(owner)
                .name(metadata.getName())
                .slug(metadata.getSlug())
                .description(metadata.getDescription())
                .filePath3d(modelAsset.getPublicId())
                .mediaAsset(modelAsset)
                .sizeXMm(metadata.getSizeXMm())
                .sizeYMm(metadata.getSizeYMm())
                .sizeZMm(metadata.getSizeZMm())
                .weight(metadata.getWeight())
                .category(metadata.getCategory())
                .build();

        for (int index = 0; index < imageAssets.size(); index++) {
            MediaAsset imageAsset = imageAssets.get(index);
            model.getImages().add(ModelImage.builder()
                    .model(model)
                    .mediaAsset(imageAsset)
                    .imagePath(imageAsset.getPublicId())
                    .isPrimary(index == 0)
                    .build());
        }
        attachParts(model, metadata);

        Model3d saved = models.saveAndFlush(model);
        lifecycle.attach(operation, operation.mediaAssetIds());
        if (snapshot != null) {
            long revision = structureSnapshots.findFirstByProductIdAndMediaAssetIdOrderBySnapshotRevisionDesc(
                            saved.getId(), modelAsset.getId())
                    .map(previous -> {
                        if (previous.getSnapshotRevision() == Long.MAX_VALUE) {
                            throw new IllegalStateException("GLB structure snapshot revision is exhausted");
                        }
                        return previous.getSnapshotRevision() + 1L;
                    })
                    .orElse(1L);
            structureSnapshots.save(new ProductGlbStructureSnapshot(UUID.randomUUID(), saved.getId(),
                    modelAsset.getId(), revision, snapshot.parserVersion(), snapshot.structureJson(),
                    java.time.Instant.now(), principal.getId()));
            entityManager.flush();
        }
        return mapper.toResponse(saved);
    }

    private record SnapshotPayload(String parserVersion, JsonNode structureJson) {
        private SnapshotPayload {
            structureJson = structureJson.deepCopy();
        }
    }

    private void attachParts(Model3d model, DesignerModelRequest metadata) {
        if (metadata.getModelParts() == null) {
            return;
        }
        for (ModelPartRequest request : metadata.getModelParts()) {
            Color color = request.getColorId() == null ? null : colors.findById(request.getColorId())
                    .orElseThrow(() -> new IllegalStateException("Requested model part color is unavailable"));
            model.getParts().add(ModelPart.builder()
                    .model(model)
                    .partName(request.getPartName())
                    .partKey(request.getPartKey())
                    .color(color)
                    .build());
        }
    }
}
