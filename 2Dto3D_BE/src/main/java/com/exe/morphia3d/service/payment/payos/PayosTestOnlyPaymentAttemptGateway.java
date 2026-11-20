package com.exe.morphia3d.service.payment.payos;

import com.exe.morphia3d.service.PaymentAttemptGateway;
import com.exe.morphia3d.service.PaymentProviderRequest;
import com.exe.morphia3d.service.PaymentProviderSubmissionResult;
import com.exe.morphia3d.service.PaymentApiException;
import com.exe.morphia3d.enums.PaymentProvider;
import com.exe.morphia3d.entity.PayosPaymentLink;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/** TEST_ONLY lifecycle adapter. It is deliberately never registered as the default application gateway. */
public final class PayosTestOnlyPaymentAttemptGateway implements PaymentAttemptGateway {
    private final boolean testOnlyEnabled;
    private final String merchantChannelId;
    private final String cancelUrl;
    private final String returnUrl;
    private final Set<String> allowedRedirectHosts;
    private final Set<String> allowedReturnHosts;
    private final Duration linkLifetime;
    private final PayosGateway gateway;
    private final PayosPaymentPersistenceService persistence;
    private final Clock clock;

    public PayosTestOnlyPaymentAttemptGateway(boolean testOnlyEnabled, String merchantChannelId,
            String cancelUrl, String returnUrl, Set<String> allowedRedirectHosts, Set<String> allowedReturnHosts,
            Duration linkLifetime,
            PayosGateway gateway, PayosPaymentPersistenceService persistence, Clock clock) {
        this.testOnlyEnabled = testOnlyEnabled;
        this.merchantChannelId = merchantChannelId;
        this.cancelUrl = cancelUrl;
        this.returnUrl = returnUrl;
        this.allowedRedirectHosts = Set.copyOf(allowedRedirectHosts);
        this.allowedReturnHosts = Set.copyOf(allowedReturnHosts);
        this.linkLifetime = linkLifetime;
        this.gateway = gateway;
        this.persistence = persistence;
        this.clock = clock;
    }

    @Override public PaymentProvider provider() { return PaymentProvider.PAYOS; }
    @Override public boolean managesSubmissionClaim() { return true; }
    @Override public boolean isAvailable() {
        return testOnlyEnabled && merchantChannelId != null && !merchantChannelId.isBlank()
                && safeHttps(cancelUrl, allowedReturnHosts) && safeHttps(returnUrl, allowedReturnHosts) && linkLifetime != null
                && !linkLifetime.isNegative() && !linkLifetime.isZero() && gateway != null && gateway.isConfigured();
    }

    @Override
    public PaymentProviderSubmissionResult submit(PaymentProviderRequest request) {
        if (!isAvailable()) return rejected("PAYMENT_PROVIDER_UNAVAILABLE");
        if (request == null || request.attemptId() == null || request.amountVnd() <= 0)
            return rejected("PAYOS_REQUEST_INVALID");

        Instant expiry = clock.instant().plus(linkLifetime);
        String description = description(request);
        PayosPaymentLink link = persistence.prepare(request.attemptId(), merchantChannelId, description, expiry);
        if ("LINK_CREATED".equals(link.getLinkState())) return accepted(link.getPaymentLinkId());
        if (!"PREPARED".equals(link.getLinkState())) return unknown("PAYOS_CREATE_REQUIRES_RECONCILIATION");
        if (!persistence.claimCreate(request.attemptId())) return unknown("PAYOS_CREATE_ALREADY_CLAIMED");

        try {
            PayosGateway.Result result = gateway.create(new PayosGateway.Command(request.attemptId(),
                    link.getOrderCode(), link.getAmountVnd(), link.getDescription(), cancelUrl, returnUrl,
                    link.getExpiresAt()));
            if (result.outcome() == PayosGateway.Outcome.UNKNOWN) {
                persistence.markCreateUnknown(request.attemptId());
                return unknown(result.errorCode());
            }
            if (result.outcome() == PayosGateway.Outcome.REJECTED) {
                persistence.markCreateRejected(request.attemptId(), result.errorCode());
                return rejected(result.errorCode());
            }
            persistence.recordCreated(request.attemptId(), new PayosPaymentPersistenceService.CreatedLink(
                    result.orderCode(), result.amountVnd(), result.paymentLinkId(), result.checkoutUrl(), result.qrCode(),
                    result.bankBin(), result.recipientAccountNumber(), result.accountName(), result.expiresAt(),
                    result.providerResponseDigest()), allowedRedirectHosts);
            return accepted(result.paymentLinkId());
        } catch (RuntimeException ambiguous) {
            persistence.markCreateUnknown(request.attemptId());
            return unknown("PAYOS_CREATE_OUTCOME_UNKNOWN");
        }
    }

    private boolean safeHttps(String value, Set<String> hosts) {
        return PayosProtocol.allowedHttps(value, hosts);
    }

    private String description(PaymentProviderRequest request) {
        String compactId = request.attemptId().toString().replace("-", "").toUpperCase(java.util.Locale.ROOT);
        return "2D" + compactId.substring(compactId.length() - 7);
    }

    private PaymentProviderSubmissionResult accepted(String linkId) {
        return new PaymentProviderSubmissionResult(PaymentProviderSubmissionResult.Outcome.ACCEPTED,
                linkId, null, null, null, null);
    }
    private PaymentProviderSubmissionResult unknown(String code) {
        return new PaymentProviderSubmissionResult(PaymentProviderSubmissionResult.Outcome.UNKNOWN,
                null, null, null, code == null ? "PAYOS_CREATE_OUTCOME_UNKNOWN" : code,
                "Payment link creation requires reconciliation");
    }
    private PaymentProviderSubmissionResult rejected(String code) {
        return new PaymentProviderSubmissionResult(PaymentProviderSubmissionResult.Outcome.REJECTED,
                null, null, null, code, "Payment link request was rejected");
    }
}
