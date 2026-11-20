package com.exe.morphia3d.repository;

import com.exe.morphia3d.entity.PaymentReconciliationCase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PaymentReconciliationCaseRepository extends JpaRepository<PaymentReconciliationCase, UUID> {
    boolean existsByOrderIdAndResolutionState(Integer orderId, String resolutionState);

    List<PaymentReconciliationCase> findByOrderIdOrderByCreatedAtDesc(Integer orderId);
}
