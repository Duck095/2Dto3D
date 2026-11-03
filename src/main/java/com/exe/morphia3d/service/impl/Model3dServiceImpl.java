package com.exe.morphia3d.service.impl;

import com.exe.morphia3d.dto.response.ModelResponse;
import com.exe.morphia3d.entity.Model3d;
import com.exe.morphia3d.entity.ModelImage;
import com.exe.morphia3d.entity.MediaAsset;
import com.exe.morphia3d.enums.ModelType;
import com.exe.morphia3d.enums.ModelStatus;
import com.exe.morphia3d.enums.MediaAssetState;
import com.exe.morphia3d.enums.MediaKind;
import com.exe.morphia3d.mapper.ModelMapper;
import com.exe.morphia3d.repository.ModelImageRepository;
import com.exe.morphia3d.repository.Model3dRepository;
import com.exe.morphia3d.service.MediaUrlResolver;
import com.exe.morphia3d.service.Model3dService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class Model3dServiceImpl implements Model3dService {

    private final Model3dRepository model3dRepository;
    private final ModelImageRepository modelImageRepository;
    private final ModelMapper modelMapper;
    private final MediaUrlResolver mediaUrlResolver;

    @Override
    @Transactional
    public void publishDesignerModel(Integer id) {
        if (id == null || id < 1) {
            throw notFound(id);
        }

        Model3d model = model3dRepository.findByIdForPublication(id)
                .orElseThrow(() -> notFound(id));
        if (model.getSourceType() != ModelType.DESIGNER_MODEL) {
            throw notFound(id);
        }
        if (model.getStatus() == ModelStatus.ACTIVE) {
            return;
        }
        if (model.getStatus() != ModelStatus.PENDING || !hasPublishableMedia(model)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Designer model is not eligible for publication");
        }

        model.setStatus(ModelStatus.ACTIVE);
        model3dRepository.flush();
    }

    private boolean hasPublishableMedia(Model3d model) {
        if (model.getName() == null || model.getName().isBlank()
                || model.getFilePath3d() == null || model.getFilePath3d().isBlank()) {
            return false;
        }

        MediaAsset modelAsset = model.getMediaAsset();
        if (!isReadyAsset(modelAsset, MediaKind.MODEL_3D)
                || !model.getFilePath3d().equals(modelAsset.getPublicId())) {
            return false;
        }

        List<ModelImage> modelImages = modelImageRepository.findByModel_IdOrderByIsPrimaryDescIdAsc(model.getId());
        if (modelImages.isEmpty()
                || modelImages.stream().filter(image -> Boolean.TRUE.equals(image.getIsPrimary())).count() != 1) {
            return false;
        }

        return modelImages.stream().allMatch(image -> {
            MediaAsset imageAsset = image.getMediaAsset();
            return image.getImagePath() != null && image.getImagePath().equals(
                    Optional.ofNullable(imageAsset).map(MediaAsset::getPublicId).orElse(null))
                    && isReadyAsset(imageAsset, MediaKind.IMAGE)
                    && modelAsset.getOperationId().equals(imageAsset.getOperationId());
        });
    }

    private boolean isReadyAsset(MediaAsset asset, MediaKind expectedKind) {
        return asset != null && asset.getState() == MediaAssetState.ATTACHED
                && mediaUrlResolver.resolve(asset, expectedKind) != null;
    }

    private ResponseStatusException notFound(Integer id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Designer model not found");
    }

    @Override
    @Transactional
    public ModelResponse getDesignerModelById(Integer id) {
        Model3d model = model3dRepository.findByIdAndSourceTypeAndStatus(
                        id, ModelType.DESIGNER_MODEL, ModelStatus.ACTIVE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Designer model không tồn tại: " + id));
        return modelMapper.toResponse(model);
    }

    @Override
    @Transactional
    public List<ModelResponse> getAllModelsBySourceType(ModelType sourceType) {
        List<Model3d> models = sourceType == ModelType.DESIGNER_MODEL
                ? model3dRepository.findBySourceTypeAndStatusOrderByCreatedAtDescIdDesc(
                        ModelType.DESIGNER_MODEL, ModelStatus.ACTIVE)
                : model3dRepository.findBySourceType(sourceType);
        return models
                .stream()
                .map(modelMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public ModelResponse getAIModelById(Integer userId, Integer id) {
        Model3d model = requireModel(id);
        if (model.getSourceType() != ModelType.AI_MODEL) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "AI model not found");
        }
        if (model.getUser() == null || !Objects.equals(model.getUser().getId(), userId)) {
            throw new AccessDeniedException("Không thể xem AI model của người dùng khác");
        }
        return modelMapper.toResponse(model);
    }

    private Model3d requireModel(Integer modelId) {
        return model3dRepository.findById(modelId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "AI model not found"));
    }

}
