package com.exe.morphia3d.service.payment.provider;

import com.exe.morphia3d.enums.PaymentProvider;
import com.exe.morphia3d.service.PaymentAttemptGateway;
import com.exe.morphia3d.service.PaymentProviderRequest;
import com.exe.morphia3d.service.PaymentProviderSubmissionResult;

import java.time.Duration;
import java.util.Set;

/** Adapter foundation requiring an injected transport; no production transport bean is supplied. */
public final class MomoPaymentAttemptGateway implements PaymentAttemptGateway {
    private final MomoProtocol.Config config;
    private final String endpoint;
    private final Set<String> allowedApiHosts;
    private final Set<String> allowedPayHosts;
    private final Set<String> allowedMerchantHosts;
    private final MomoTransport transport;
    private final Duration timeout;

    public MomoPaymentAttemptGateway(MomoProtocol.Config config, String endpoint, Set<String> allowedApiHosts,
                                     Set<String> allowedPayHosts, Set<String> allowedMerchantHosts,
                                     MomoTransport transport, Duration timeout) {
        this.config = config; this.endpoint = endpoint;
        this.allowedApiHosts = allowedApiHosts == null ? Set.of() : Set.copyOf(allowedApiHosts);
        this.allowedPayHosts = allowedPayHosts == null ? Set.of() : Set.copyOf(allowedPayHosts);
        this.allowedMerchantHosts = allowedMerchantHosts == null ? Set.of() : Set.copyOf(allowedMerchantHosts);
        this.transport = transport; this.timeout = timeout;
    }

    @Override public PaymentProvider provider() { return PaymentProvider.MOMO; }
    @Override public boolean isAvailable() {
        return config != null && RedirectUrlPolicy.allowedHttps(endpoint, allowedApiHosts) && !allowedPayHosts.isEmpty()
                && RedirectUrlPolicy.allowedHttps(config.redirectUrl(), allowedMerchantHosts)
                && RedirectUrlPolicy.allowedHttps(config.ipnUrl(), allowedMerchantHosts)
                && transport != null && timeout != null && timeout.compareTo(Duration.ofSeconds(30)) >= 0;
    }

    @Override public PaymentProviderSubmissionResult submit(PaymentProviderRequest request) {
        if (!isAvailable() || request == null || request.attemptId() == null
                || !ProviderReference.forAttempt(PaymentProvider.MOMO, request.attemptId()).equals(request.merchantReference())) {
            return new PaymentProviderSubmissionResult(PaymentProviderSubmissionResult.Outcome.REJECTED,
                    null, null, null, "PROVIDER_REQUEST_INVALID", "Provider request is not compatible with the verified protocol");
        }
        MomoProtocol.CreateRequest create = MomoProtocol.createRequest(config, request.attemptId(), request.amountVnd(), request.idempotencyKey());
        final String responseBody;
        try { responseBody = transport.postJson(endpoint, create.body(), timeout); }
        catch (RuntimeException ambiguous) { return new PaymentProviderSubmissionResult(PaymentProviderSubmissionResult.Outcome.UNKNOWN,
                create.requestId(), null, null, "PROVIDER_RESULT_UNKNOWN", "Provider response requires reconciliation"); }
        final java.util.Map<String, String> response;
        try { response = MomoProtocol.parseCreateResponse(responseBody); }
        catch (IllegalArgumentException malformed) { return new PaymentProviderSubmissionResult(PaymentProviderSubmissionResult.Outcome.UNKNOWN,
                create.requestId(), null, null, "PROVIDER_RESPONSE_INVALID", "Provider response requires reconciliation"); }
        if (!create.requestId().equals(response.get("requestId")) || !create.requestId().equals(response.get("orderId"))
                || !config.partnerCode().equals(response.get("partnerCode"))
                || !Long.toString(request.amountVnd()).equals(response.get("amount"))) {
            return new PaymentProviderSubmissionResult(PaymentProviderSubmissionResult.Outcome.UNKNOWN,
                    create.requestId(), null, null, "PROVIDER_RESPONSE_MISMATCH", "Provider response requires reconciliation");
        }
        if (!"0".equals(response.get("resultCode"))) return new PaymentProviderSubmissionResult(
                PaymentProviderSubmissionResult.Outcome.UNKNOWN, create.requestId(), null, null,
                "PROVIDER_RESULT_UNRESOLVED", "Provider response requires status reconciliation");
        String payUrl = response.get("payUrl");
        if (!MomoProtocol.isSafePayUrl(payUrl, allowedPayHosts)) return new PaymentProviderSubmissionResult(
                PaymentProviderSubmissionResult.Outcome.UNKNOWN, create.requestId(), null, null,
                "PROVIDER_REDIRECT_UNSAFE", "Provider redirect requires review");
        return new PaymentProviderSubmissionResult(PaymentProviderSubmissionResult.Outcome.ACCEPTED,
                create.requestId(), null, payUrl, null, null);
    }
}
