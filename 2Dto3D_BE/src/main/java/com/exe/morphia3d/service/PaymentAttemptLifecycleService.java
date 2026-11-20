package com.exe.morphia3d.service;

import com.exe.morphia3d.domain.payment.PaymentAttemptStatus;
import com.exe.morphia3d.dto.response.PaymentAttemptResponse;
import com.exe.morphia3d.entity.PaymentAttempt;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class PaymentAttemptLifecycleService {
    private final PaymentAttemptStateService stateService;

    public PaymentAttemptLifecycleService(PaymentAttemptStateService stateService) {
        this.stateService = stateService;
    }

    public PaymentAttemptResponse dispatchNewAttempt(UUID attemptId, PaymentAttemptGateway gateway) {
        if (gateway == null || !gateway.isAvailable()) {
            throw new PaymentApiException(503, "PAYMENT_PROVIDER_UNAVAILABLE",
                    "The selected payment provider is not verified and available");
        }
        if (!gateway.managesSubmissionClaim() && !stateService.claimSubmission(attemptId))
            return response(stateService.get(attemptId), null);
        PaymentAttempt attempt = stateService.get(attemptId);
        PaymentProviderSubmissionResult result;
        try {
            result = gateway.submit(new PaymentProviderRequest(attempt.getAttemptId(), attempt.getOrderId(),
                    attempt.getAmountVnd(), attempt.getIdempotencyKey(), attempt.getMerchantReference()));
        } catch (RuntimeException ambiguous) {
            PaymentAttempt unknown = stateService.markSubmissionUnknown(attemptId,
                    "PROVIDER_RESULT_UNKNOWN", "Provider response could not be durably confirmed");
            return response(unknown, null);
        }
        PaymentAttempt updated = stateService.recordSubmission(attemptId, result);
        // Provider contract and redirect-host validation have not passed review; keep redirects disabled.
        return response(updated, null);
    }

    public PaymentAttemptResponse response(PaymentAttempt attempt, String redirectUrl) {
        return response(attempt, false, redirectUrl);
    }

    public PaymentAttemptResponse response(PaymentAttempt attempt, boolean retryAllowed, String redirectUrl) {
        return new PaymentAttemptResponse(attempt.getAttemptId(), attempt.getOrderId(), attempt.getProvider(),
                attempt.getAmountVnd(), attempt.getStatus(), retryAllowed, redirectUrl, attempt.getErrorCode());
    }

    public PaymentAttempt applyVerifiedEvent(VerifiedPaymentEvent event) {
        return stateService.applyVerifiedEvent(event);
    }

    /** Recovery may safely claim INITIATED attempts; SUBMITTING attempts are never resent. */
    public PaymentAttemptResponse recoverUndispatchedAttempt(UUID attemptId, PaymentAttemptGateway gateway) {
        return dispatchNewAttempt(attemptId, gateway);
    }

    /** A watchdog may call this after its configured lease expires; it records UNKNOWN without resubmitting. */
    public PaymentAttemptResponse markStaleSubmissionUnknown(UUID attemptId) {
        return response(stateService.markSubmissionUnknown(attemptId,
                "SUBMISSION_OUTCOME_UNKNOWN", "Submission outcome requires provider reconciliation"), null);
    }
}
