package com.exe.morphia3d.repository;

import com.exe.morphia3d.entity.ValidatedSlicingRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;

public interface ValidatedSlicingRunRepository extends JpaRepository<ValidatedSlicingRun, Long> {
    List<ValidatedSlicingRun> findAllByModelIdAndValidatedTrueOrderByValidatedAtDesc(Integer modelId);
    Optional<ValidatedSlicingRun> findFirstByModelIdAndMaterialIdAndPrinterIdAndConfigurationFingerprintAndValidatedTrueOrderByValidatedAtDesc(
            Integer modelId, Integer materialId, Integer printerId, String configurationFingerprint);
}
