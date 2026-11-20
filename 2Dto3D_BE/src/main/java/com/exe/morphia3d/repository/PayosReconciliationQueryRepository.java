package com.exe.morphia3d.repository;

import com.exe.morphia3d.entity.PayosReconciliationQuery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PayosReconciliationQueryRepository extends JpaRepository<PayosReconciliationQuery, UUID> {
    List<PayosReconciliationQuery> findByAttemptIdOrderByRequestedAtDesc(UUID attemptId);
    java.util.Optional<PayosReconciliationQuery> findByMerchantChannelIdAndQueryRequestId(String merchantChannelId, String queryRequestId);
}
