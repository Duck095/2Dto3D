package com.exe.morphia3d.service.impl;

import com.exe.morphia3d.entity.Material;
import com.exe.morphia3d.enums.MaterialStatus;
import com.exe.morphia3d.repository.MaterialRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MaterialServiceRetirementTest {

    private final Map<Integer, Material> materials = new HashMap<>();
    private final List<Material> savedMaterials = new ArrayList<>();
    private final List<Material> deletedMaterials = new ArrayList<>();
    private final MaterialRepository materialRepository = (MaterialRepository) Proxy.newProxyInstance(
            MaterialRepository.class.getClassLoader(),
            new Class<?>[]{MaterialRepository.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "findById", "findByIdForUpdate" -> Optional.ofNullable(materials.get((Integer) args[0]));
                case "save" -> {
                    Material material = (Material) args[0];
                    savedMaterials.add(material);
                    materials.put(material.getId(), material);
                    yield material;
                }
                case "delete" -> {
                    deletedMaterials.add((Material) args[0]);
                    yield null;
                }
                case "toString" -> "MaterialRepository test fake";
                default -> throw new UnsupportedOperationException("Unexpected repository call: " + method);
            });
    private final MaterialServiceImpl service = new MaterialServiceImpl(
            materialRepository, null, null, null);

    @Test
    void removeMaterialMarksUnavailableAndPersistsInsteadOfDeleting() {
        Material material = Material.builder().id(7).isAvailable(MaterialStatus.AVAILABLE).build();
        materials.put(7, material);

        service.removeMaterial(7);

        assertThat(material.getIsAvailable()).isEqualTo(MaterialStatus.UNAVAILABLE);
        assertThat(savedMaterials).containsExactly(material);
        assertThat(deletedMaterials).isEmpty();
    }

    @Test
    void repeatedRemoveMaterialRemainsUnavailableAndPersistsSafely() {
        Material material = Material.builder().id(7).isAvailable(MaterialStatus.UNAVAILABLE).build();
        materials.put(7, material);

        service.removeMaterial(7);
        service.removeMaterial(7);

        assertThat(material.getIsAvailable()).isEqualTo(MaterialStatus.UNAVAILABLE);
        assertThat(savedMaterials).containsExactly(material, material);
        assertThat(deletedMaterials).isEmpty();
    }

    @Test
    void missingMaterialKeepsExistingNotFoundException() {
        assertThatThrownBy(() -> service.removeMaterial(99))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessage("Material không tồn tại: 99");
    }
}
