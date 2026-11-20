package com.exe.morphia3d.repository;

import com.exe.morphia3d.entity.Material;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;
import jakarta.persistence.LockModeType;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Repository
public interface MaterialRepository extends JpaRepository<Material, Integer> {

    @EntityGraph(attributePaths = {"materialColors", "materialColors.color"})
    @Query("SELECT m FROM Material m")
    List<Material> findAllWithColors();

    /** Serializes every mutation of a material and its color associations. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT material FROM Material material WHERE material.id = :id")
    Optional<Material> findByIdForUpdate(@Param("id") Integer id);
}
