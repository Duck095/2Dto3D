package com.exe.morphia3d.repository;

import com.exe.morphia3d.entity.ModelImage;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface ModelImageRepository extends JpaRepository<ModelImage, Integer> {
    @EntityGraph(attributePaths = {"model", "mediaAsset"})
    List<ModelImage> findByModel_IdInOrderByIsPrimaryDescIdAsc(Collection<Integer> modelIds);

    @EntityGraph(attributePaths = "mediaAsset")
    List<ModelImage> findByModel_IdOrderByIsPrimaryDescIdAsc(Integer modelId);

    boolean existsByMediaAsset_Id(Long mediaAssetId);
}
