package com.exe.morphia3d.service;

import com.exe.morphia3d.domain.payment.PaymentAttemptEvent;
import com.exe.morphia3d.domain.payment.PaymentAttemptStatus;
import com.exe.morphia3d.entity.PaymentAttempt;
import com.exe.morphia3d.enums.PaymentProvider;
import com.exe.morphia3d.service.payment.payos.PayosGateway;
import com.exe.morphia3d.service.payment.payos.PayosPaymentPersistenceService;
import com.exe.morphia3d.service.payment.payos.PayosProtocol;
import com.exe.morphia3d.service.payment.provider.ProviderPaymentState;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.Map;
import java.util.UUID;

/** Applies only cryptographically verified, identity-matched provider query evidence. */
public class PayosReconciliationProcessingService {
    private final Map<String, PayosGateway> gatewaysByChannel;
    private final PayosPaymentPersistenceService persistence;
    private final PaymentAttemptPersistenceService attempts;
    private final PaymentAttemptLifecycleService lifecycle;

    public PayosReconciliationProcessingService(@Qualifier("payosQueryGateways") Map<String, PayosGateway> gatewaysByChannel,
            PayosPaymentPersistenceService persistence, PaymentAttemptPersistenceService attempts,
            PaymentAttemptLifecycleService lifecycle) {
        this.gatewaysByChannel = Map.copyOf(gatewaysByChannel);
        this.persistence = persistence;
        this.attempts = attempts;
        this.lifecycle = lifecycle;
    }

    @Transactional
    public ReconciliationResult reconcileUnknown(UUID attemptId, String merchantChannelId) {
        PaymentAttempt attempt = attempts.getAttemptForLifecycle(attemptId);
        if (attempt.getProvider() != PaymentProvider.PAYOS
                || (attempt.getStatus() != PaymentAttemptStatus.UNKNOWN
                && attempt.getStatus() != PaymentAttemptStatus.SUCCEEDED))
            throw new PaymentApiException(409, "PAYOS_ATTEMPT_NOT_RECONCILABLE",
                    "Only UNKNOWN or already SUCCEEDED payOS attempts may be reconciled");
        PayosGateway gateway = gatewaysByChannel.get(merchantChannelId);
        if (gateway == null || !gateway.isConfigured())
            throw new PaymentApiException(503, "PAYOS_CHANNEL_UNAVAILABLE", "No verified payOS query adapter is configured for this channel");
        PayosProtocol.ExpectedPayment expected = persistence.expectedPayment(attemptId, merchantChannelId);
        String requestId = UUID.randomUUID().toString();
        PayosProtocol.VerifiedPaymentLinkQuery evidence = gateway.queryEvidence(merchantChannelId, requestId, expected);
        if (evidence == null) return new ReconciliationResult(null, PaymentAttemptStatus.UNKNOWN, false);
        var query = persistence.storeReconciliationQuery(evidence);
        PaymentAttemptStatus appliedStatus = attempt.getStatus();
        if ("SUCCEEDED".equals(query.getOutcome()) && evidence.state() == ProviderPaymentState.SUCCEEDED
                && "PAID".equals(query.getProviderStatus())
                && query.getAmountPaidVnd() != null && query.getAmountPaidVnd().equals(attempt.getAmountVnd())) {
            if (attempt.getStatus() == PaymentAttemptStatus.UNKNOWN) {
                VerifiedPaymentEvent event = new VerifiedPaymentEvent(PaymentProvider.PAYOS, attemptId,
                        attempt.getMerchantReference(), "PAYOS_QUERY:" + requestId, query.getResponseDigest(),
                        query.getAmountPaidVnd(), null, PaymentAttemptEvent.SUCCESS,
                        query.getCompletedAt(), "PAYOS_SIGNED_QUERY");
                appliedStatus = lifecycle.applyVerifiedEvent(event).getStatus();
            } else {
                // A later exact paid query corroborates a webhook settlement; it is not a second settlement event.
                appliedStatus = PaymentAttemptStatus.SUCCEEDED;
            }
        } else if ("FAILED".equals(query.getOutcome()) && evidence.state() == ProviderPaymentState.CANCELED) {
            VerifiedPaymentEvent event = new VerifiedPaymentEvent(PaymentProvider.PAYOS, attemptId,
                    attempt.getMerchantReference(), "PAYOS_QUERY:" + requestId, query.getResponseDigest(),
                    attempt.getAmountVnd(), null, PaymentAttemptEvent.CANCEL,
                    query.getCompletedAt(), "PAYOS_SIGNED_QUERY");
            appliedStatus = lifecycle.applyVerifiedEvent(event).getStatus();
        }
        return new ReconciliationResult(query.getQueryId(), appliedStatus,
                appliedStatus != PaymentAttemptStatus.UNKNOWN);
    }

    public record ReconciliationResult(UUID queryId, PaymentAttemptStatus status, boolean resolved) { }
}
