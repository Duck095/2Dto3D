package com.exe.morphia3d.service;

import com.exe.morphia3d.domain.payment.PaymentAttemptEvent;
import com.exe.morphia3d.domain.payment.PaymentAttemptStatus;
import com.exe.morphia3d.entity.PayosWebhookReceipt;
import com.exe.morphia3d.entity.PaymentAttempt;
import com.exe.morphia3d.enums.PaymentProvider;
import com.exe.morphia3d.service.payment.payos.PayosPaymentPersistenceService;
import com.exe.morphia3d.service.payment.payos.PayosProtocol;
import com.exe.morphia3d.service.payment.payos.PayosVerificationException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.Map;
import java.util.Objects;

/** Internal application boundary for verified payOS webhook events. No HTTP controller exposes this service. */
public class PayosWebhookProcessingService {
    private final Map<String, String> checksumKeysByChannel;
    private final PayosPaymentPersistenceService persistence;
    private final PaymentAttemptPersistenceService attempts;
    private final PaymentAttemptLifecycleService lifecycle;

    public PayosWebhookProcessingService(@Qualifier("payosWebhookChecksumKeys") Map<String, String> checksumKeysByChannel,
            PayosPaymentPersistenceService persistence, PaymentAttemptPersistenceService attempts,
            PaymentAttemptLifecycleService lifecycle) {
        this.checksumKeysByChannel = Map.copyOf(checksumKeysByChannel);
        this.persistence = persistence;
        this.attempts = attempts;
        this.lifecycle = lifecycle;
    }

    /** Receipt persistence and Payment/Order transition share one transaction; return is safe to ACK after commit. */
    @Transactional
    public ProcessingResult process(String merchantChannelId, String rawPayload) {
        String checksumKey = checksumKeysByChannel.get(merchantChannelId);
        if (checksumKey == null || checksumKey.isBlank()) {
            throw new PayosVerificationException("PAYMENT_CHANNEL_UNKNOWN", "payOS merchant channel is not configured");
        }
        PayosProtocol.VerifiedWebhook verified = PayosProtocol.verifyWebhookEvidence(
                rawPayload, merchantChannelId, checksumKey);
        PayosWebhookReceipt receipt = persistence.recordVerifiedWebhook(verified);
        return applyReceipt(receipt);
    }

    /** Recovery hook for paid callbacks durably received before create-response persistence completed. */
    @Transactional
    public int applyDurableMatchedReceipts(java.util.UUID attemptId) {
        int applied = 0;
        for (PayosWebhookReceipt receipt : persistence.matchedReceipts(attemptId)) {
            applyReceipt(receipt);
            applied++;
        }
        return applied;
    }

    private ProcessingResult applyReceipt(PayosWebhookReceipt receipt) {
        if (!"MATCHED".equals(receipt.getDisposition()) || !receipt.isSignatureVerified()
                || !"PAID".equals(receipt.getProviderStatus()) || !"VND".equals(receipt.getCurrency())
                || receipt.getAttemptId() == null) {
            return new ProcessingResult(receipt.getReceiptId(), receipt.getDisposition(), null);
        }
        PaymentAttempt attempt = attempts.getAttemptForLifecycle(receipt.getAttemptId());
        if (attempt.getProvider() != PaymentProvider.PAYOS || attempt.getAmountVnd() == null
                || !Objects.equals(attempt.getAmountVnd(), receipt.getAmountVnd())) {
            throw new PaymentApiException(409, "PAYOS_RECEIPT_ATTEMPT_MISMATCH",
                    "Verified receipt does not match its payment attempt");
        }
        VerifiedPaymentEvent event = new VerifiedPaymentEvent(PaymentProvider.PAYOS, attempt.getAttemptId(),
                attempt.getMerchantReference(), eventKey(receipt), receipt.getEventDigest(), receipt.getAmountVnd(),
                receipt.getBankReference(), PaymentAttemptEvent.SUCCESS, receipt.getSignatureVerifiedAt(),
                "PAYOS_SIGNED_WEBHOOK");
        PaymentAttempt updated = lifecycle.applyVerifiedEvent(event);
        return new ProcessingResult(receipt.getReceiptId(), receipt.getDisposition(), updated.getStatus());
    }

    private String eventKey(PayosWebhookReceipt receipt) {
        return "PAYOS:" + receipt.getMerchantChannelId() + ":" + receipt.getBankReference();
    }

    public record ProcessingResult(java.util.UUID receiptId, String disposition, PaymentAttemptStatus paymentStatus) { }
}
