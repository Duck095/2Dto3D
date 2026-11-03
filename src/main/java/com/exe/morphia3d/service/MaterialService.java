package com.exe.morphia3d.service;

import com.exe.morphia3d.dto.request.ColorQuantityRequest;
import com.exe.morphia3d.dto.request.MaterialRequest;
import com.exe.morphia3d.dto.response.MaterialResponse;
import com.exe.morphia3d.entity.Material;

import java.util.List;

public interface MaterialService {
    void addMaterial(MaterialRequest request);
    void removeMaterial(Integer id);

    List<MaterialResponse> getAllMaterials();

    void addMaterialColor(Integer materialId, ColorQuantityRequest request);
    void removeMaterialColor(Integer materialId, Integer colorId);
}
