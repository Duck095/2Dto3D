package com.exe.morphia3d.repository;

import com.exe.morphia3d.entity.ProductMeshConfigurationRevision;
import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

/** Narrow insert/read repository for immutable mesh configuration history. */
public interface ProductMeshConfigurationRevisionRepository extends Repository<ProductMeshConfigurationRevision, UUID> {
    <S extends ProductMeshConfigurationRevision> S save(S revision);

    Optional<ProductMeshConfigurationRevision> findFirstByProductIdAndMediaAssetIdOrderByConfigRevisionDesc(
            Integer productId, Long mediaAssetId);

    Optional<ProductMeshConfigurationRevision> findFirstByProductIdAndMediaAssetIdAndStructureSnapshotIdOrderByConfigRevisionDesc(
            Integer productId, Long mediaAssetId, UUID structureSnapshotId);

    boolean existsByMediaAssetId(Long mediaAssetId);
}
