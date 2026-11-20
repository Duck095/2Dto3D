package com.exe.morphia3d.repository;

import com.exe.morphia3d.entity.PayosWebhookReceipt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PayosWebhookReceiptRepository extends JpaRepository<PayosWebhookReceipt, UUID> {
    List<PayosWebhookReceipt> findByAttemptIdOrderByReceivedAtDesc(UUID attemptId);
    List<PayosWebhookReceipt> findByMerchantChannelIdAndBankReferenceOrderByReceivedAtAsc(String merchantChannelId, String bankReference);
    List<PayosWebhookReceipt> findByMerchantChannelIdAndOrderCodeAndDispositionOrderByReceivedAtAsc(
            String merchantChannelId, Long orderCode, String disposition);
}
