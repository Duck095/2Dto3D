package com.exe.morphia3d.repository;

import com.exe.morphia3d.entity.MaterialColor;
import com.exe.morphia3d.entity.MaterialColorId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MaterialColorRepository extends JpaRepository<MaterialColor, MaterialColorId> {

    Optional<MaterialColor> findByMaterial_IdAndColor_Id(Integer materialId, Integer colorId);

    List<MaterialColor> findAllByMaterial_Id(Integer materialId);

    List<MaterialColor> findAllByColor_Id(Integer colorId);

    boolean existsByMaterial_IdAndColor_Id(Integer materialId, Integer colorId);

    void deleteByMaterial_IdAndColor_Id(Integer materialId, Integer colorId);

    List<MaterialColor> findByMaterialId(Integer id);
}