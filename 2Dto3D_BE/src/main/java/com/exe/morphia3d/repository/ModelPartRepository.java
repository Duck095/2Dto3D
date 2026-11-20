package com.exe.morphia3d.repository;

import com.exe.morphia3d.entity.ModelPart;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ModelPartRepository extends JpaRepository<ModelPart, Integer> {
    List<ModelPart> findByModel_IdOrderByIdAsc(Integer modelId);
}
