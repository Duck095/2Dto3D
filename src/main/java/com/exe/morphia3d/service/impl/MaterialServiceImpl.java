package com.exe.morphia3d.service.impl;

import com.exe.morphia3d.dto.request.ColorQuantityRequest;
import com.exe.morphia3d.dto.request.MaterialRequest;
import com.exe.morphia3d.dto.response.ColorResponse;
import com.exe.morphia3d.dto.response.MaterialResponse;
import com.exe.morphia3d.entity.Color;
import com.exe.morphia3d.entity.Material;
import com.exe.morphia3d.entity.MaterialColor;
import com.exe.morphia3d.entity.MaterialColorId;
import com.exe.morphia3d.enums.MaterialStatus;
import com.exe.morphia3d.mapper.MaterialColorMapper;
import com.exe.morphia3d.mapper.MaterialMapper;
import com.exe.morphia3d.repository.ColorRepository;
import com.exe.morphia3d.repository.MaterialColorRepository;
import com.exe.morphia3d.repository.MaterialRepository;
import com.exe.morphia3d.service.MaterialService;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MaterialServiceImpl implements MaterialService {

    private final MaterialRepository materialRepository;
    private final ColorRepository colorRepository;
    private final MaterialColorRepository materialColorRepository;
    private final MaterialMapper materialMapper;

    @Override
    @Transactional
    public void addMaterial(MaterialRequest request) {
        // 1. Build Material trước (chưa có colors)
        Material material = Material.builder()
                .name(request.getName())
                .type(request.getType())
                .pricePerGram(request.getPricePerGram())
                .stockQuantity(request.getStockQuantity())
                .unit(request.getUnit() != null ? request.getUnit() : "g")
                .isAvailable(MaterialStatus.AVAILABLE)
                .build();

        materialRepository.save(material);

        // 2. Build danh sách MaterialColor
        if (request.getColors() != null && !request.getColors().isEmpty()) {
            Set<MaterialColor> materialColors = new HashSet<>();

            for (ColorQuantityRequest cq : request.getColors()) {
                Color color = colorRepository.findById(cq.getColorId())
                        .orElseThrow(() -> new EntityNotFoundException(
                                "Color không tồn tại: " + cq.getColorId()));

                MaterialColor mc = MaterialColor.builder()
                        .id(new MaterialColorId(material.getId(), color.getId())) // material.getId() sẽ null nếu chưa save
                        .material(material)
                        .color(color)
                        .quantity(cq.getQuantity())
                        .build();

                materialColors.add(mc);
            }
            material.setMaterialColors(materialColors);
        }

        // 3. Save — cascade sẽ tự insert vào material_colors
        materialRepository.save(material);
    }

    @Override
    @Transactional
    public void removeMaterial(Integer id) {
        Material material = materialRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Material không tồn tại: " + id));
        material.setIsAvailable(MaterialStatus.UNAVAILABLE);
        materialRepository.save(material);
    }

    @Override
    public List<MaterialResponse> getAllMaterials() {
        return materialRepository.findAllWithColors().stream()
                .map(materialMapper::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void addMaterialColor(Integer materialId, ColorQuantityRequest request) {
        if (materialColorRepository.existsByMaterial_IdAndColor_Id(materialId, request.getColorId())) {
            throw new IllegalStateException("Material đã có color này, hãy dùng API update quantity thay vì add");
        }

        Material material = materialRepository.findById(materialId)
                .orElseThrow(() -> new EntityNotFoundException("Material không tồn tại: " + materialId));
        Color color = colorRepository.findById(request.getColorId())
                .orElseThrow(() -> new EntityNotFoundException("Color không tồn tại: " + request.getColorId()));

        MaterialColor mc = MaterialColor.builder()
                .id(new MaterialColorId(materialId, color.getId()))
                .material(material)
                .color(color)
                .quantity(request.getQuantity())
                .build();

        materialColorRepository.save(mc);
    }

    @Override
    @Transactional
    public void removeMaterialColor(Integer materialId, Integer colorId) {
        MaterialColor mc = materialColorRepository.findByMaterial_IdAndColor_Id(materialId, colorId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Material " + materialId + " không có color " + colorId));
        materialColorRepository.delete(mc);
    }
}
