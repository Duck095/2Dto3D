package com.exe.morphia3d.repository;

import com.exe.morphia3d.entity.PriceEstimation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PriceEstimationRepository extends JpaRepository<PriceEstimation, Integer> {
}
