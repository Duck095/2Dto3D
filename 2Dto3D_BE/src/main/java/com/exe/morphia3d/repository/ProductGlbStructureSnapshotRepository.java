package com.exe.morphia3d.repository;

import com.exe.morphia3d.entity.ProductGlbStructureSnapshot;
import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

/** Narrow insert/read repository for immutable GLB structure history. */
public interface ProductGlbStructureSnapshotRepository extends Repository<ProductGlbStructureSnapshot, UUID> {
    <S extends ProductGlbStructureSnapshot> S save(S snapshot);

    Optional<ProductGlbStructureSnapshot> findByStructureSnapshotId(UUID structureSnapshotId);

    Optional<ProductGlbStructureSnapshot> findFirstByProductIdAndMediaAssetIdOrderBySnapshotRevisionDesc(
            Integer productId, Long mediaAssetId);

    boolean existsByMediaAssetId(Long mediaAssetId);
}
