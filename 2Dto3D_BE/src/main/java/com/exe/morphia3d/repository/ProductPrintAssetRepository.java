package com.exe.morphia3d.repository;

import com.exe.morphia3d.entity.ProductPrintAsset;
import com.exe.morphia3d.entity.ProductPrintAssetId;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductPrintAssetRepository extends JpaRepository<ProductPrintAsset, ProductPrintAssetId> {

    @EntityGraph(attributePaths = "mediaAsset")
    List<ProductPrintAsset> findAllByIdProductIdOrderByUploadedAtDescIdMediaAssetIdDesc(Integer productId);

    boolean existsByIdMediaAssetId(Long mediaAssetId);
}
