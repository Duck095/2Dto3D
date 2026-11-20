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
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

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
        validateMaterial(request);
        List<Color> selectedColors = resolveColors(request.getColors());
        Material material = Material.builder()
                .name(request.getName().trim())
                .type(normalizeOptional(request.getType()))
                .pricePerGram(request.getPricePerGram())
                .stockQuantity(request.getStockQuantity())
                .unit(request.getUnit() != null && !request.getUnit().isBlank() ? request.getUnit().trim() : "g")
                .isAvailable(MaterialStatus.AVAILABLE)
                .build();

        materialRepository.save(material);
        materialColorRepository.saveAll(buildMaterialColors(material, request.getColors(), selectedColors));
    }

    @Override
    @Transactional
    public void updateMaterial(Integer id, MaterialRequest request) {
        validateMaterial(request);
        Material material = lockMaterial(id);
        List<Color> selectedColors = request.getColors() == null ? null : resolveColors(request.getColors());

        material.setName(request.getName().trim());
        material.setType(normalizeOptional(request.getType()));
        material.setPricePerGram(request.getPricePerGram());
        material.setStockQuantity(request.getStockQuantity());
        material.setUnit(request.getUnit() != null && !request.getUnit().isBlank() ? request.getUnit().trim() : "g");
        materialRepository.save(material);

        if (request.getColors() != null) {
            List<MaterialColor> previous = materialColorRepository.findAllByMaterial_Id(id);
            materialColorRepository.deleteAll(previous);
            materialColorRepository.flush();
            materialColorRepository.saveAll(buildMaterialColors(material, request.getColors(), selectedColors));
        }
    }

    @Override
    @Transactional
    public void removeMaterial(Integer id) {
        Material material = lockMaterial(id);
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
        validateColorQuantity(request);
        Material material = lockMaterial(materialId);
        if (materialColorRepository.existsByMaterial_IdAndColor_Id(materialId, request.getColorId())) {
            throw new IllegalStateException("Material đã có color này, hãy dùng API update quantity thay vì add");
        }

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
        lockMaterial(materialId);
        MaterialColor mc = materialColorRepository.findByMaterial_IdAndColor_Id(materialId, colorId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Material " + materialId + " không có color " + colorId));
        materialColorRepository.delete(mc);
    }

    private Material lockMaterial(Integer id) {
        return materialRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new EntityNotFoundException("Material không tồn tại: " + id));
    }

    private void validateMaterial(MaterialRequest request) {
        if (request == null || request.getName() == null || request.getName().isBlank()
                || request.getName().trim().length() > 100
                || (request.getType() != null && request.getType().trim().length() > 50)
                || request.getPricePerGram() == null || !Double.isFinite(request.getPricePerGram())
                || request.getPricePerGram() < 0
                || request.getStockQuantity() == null || !Double.isFinite(request.getStockQuantity())
                || request.getStockQuantity() < 0
                || (request.getUnit() != null && request.getUnit().trim().length() > 20)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid material fields");
        }
    }

    private List<Color> resolveColors(List<ColorQuantityRequest> requests) {
        if (requests == null || requests.isEmpty()) return List.of();
        Set<Integer> ids = new HashSet<>();
        List<Color> result = new java.util.ArrayList<>(requests.size());
        for (ColorQuantityRequest request : requests) {
            validateColorQuantity(request);
            if (!ids.add(request.getColorId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate colorId in material colors");
            }
            result.add(colorRepository.findById(request.getColorId())
                    .orElseThrow(() -> new EntityNotFoundException("Color không tồn tại: " + request.getColorId())));
        }
        return result;
    }

    private List<MaterialColor> buildMaterialColors(Material material,
                                                     List<ColorQuantityRequest> requests,
                                                     List<Color> colors) {
        if (requests == null || requests.isEmpty()) return List.of();
        List<MaterialColor> result = new java.util.ArrayList<>(requests.size());
        for (int index = 0; index < requests.size(); index++) {
            Color color = colors.get(index);
            ColorQuantityRequest request = requests.get(index);
            result.add(MaterialColor.builder()
                    .id(new MaterialColorId(material.getId(), color.getId()))
                    .material(material)
                    .color(color)
                    .quantity(request.getQuantity())
                    .build());
        }
        return result;
    }

    private void validateColorQuantity(ColorQuantityRequest request) {
        if (request == null || request.getColorId() == null || request.getColorId() < 1
                || request.getQuantity() == null || !Double.isFinite(request.getQuantity())
                || request.getQuantity() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid material color");
        }
    }

    private String normalizeOptional(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
