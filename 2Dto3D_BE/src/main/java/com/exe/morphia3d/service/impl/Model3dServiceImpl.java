package com.exe.morphia3d.service.impl;

import com.exe.morphia3d.dto.response.ModelResponse;
import com.exe.morphia3d.entity.Model3d;
import com.exe.morphia3d.enums.ModelStatus;
import com.exe.morphia3d.enums.ModelType;
import com.exe.morphia3d.mapper.ModelMapper;
import com.exe.morphia3d.repository.Model3dRepository;
import com.exe.morphia3d.service.Model3dService;
import com.exe.morphia3d.service.admin3d.Admin3dRestrictedMutationGateway;
import com.exe.morphia3d.feature.FeatureFamily;
import com.exe.morphia3d.feature.FeatureGate;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class Model3dServiceImpl implements Model3dService {

    private final Model3dRepository model3dRepository;
    private final ModelMapper modelMapper;
    private final FeatureGate featureGate;
    private final Admin3dRestrictedMutationGateway restrictedMutations;

    @Override
    @Transactional
    public void publishDesignerModel(Integer id) {
        if (id == null || id < 1) {
            throw notFound(id);
        }

        restrictedMutations.publish(id);
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
        if (sourceType == ModelType.AI_MODEL) featureGate.requireEnabled(FeatureFamily.AI);
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
        featureGate.requireEnabled(FeatureFamily.AI);
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
