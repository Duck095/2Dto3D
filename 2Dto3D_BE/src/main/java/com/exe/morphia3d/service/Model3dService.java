package com.exe.morphia3d.service;

import com.exe.morphia3d.dto.response.ModelResponse;
import com.exe.morphia3d.enums.ModelType;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public interface Model3dService {
    void publishDesignerModel(Integer id);
    ModelResponse getDesignerModelById(Integer id);
    List<ModelResponse> getAllModelsBySourceType(ModelType sourceType);
    ModelResponse getAIModelById(Integer userId, Integer id);
}
