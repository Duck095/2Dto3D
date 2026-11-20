package com.exe.morphia3d.repository;

import com.exe.morphia3d.entity.PayosPaymentLink;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface PayosPaymentLinkRepository extends JpaRepository<PayosPaymentLink, UUID> {
    Optional<PayosPaymentLink> findByPaymentLinkId(String paymentLinkId);
    Optional<PayosPaymentLink> findByMerchantChannelIdAndOrderCode(String merchantChannelId, Long orderCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select link from PayosPaymentLink link where link.attemptId = :attemptId")
    Optional<PayosPaymentLink> findByAttemptIdForUpdate(@Param("attemptId") UUID attemptId);
}
