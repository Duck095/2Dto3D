package com.exe.morphia3d.repository;

import com.exe.morphia3d.entity.Material;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.List;

@Repository
public interface MaterialRepository extends JpaRepository<Material, Integer> {

    @EntityGraph(attributePaths = {"materialColors", "materialColors.color"})
    @Query("SELECT m FROM Material m")
    List<Material> findAllWithColors();
}
