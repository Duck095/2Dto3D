package com.exe.morphia3d.service.payment.payos;

import com.exe.morphia3d.service.payment.provider.ProviderPaymentState;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Offline-only adapter over an injected transport; it performs no automatic create retries. */
public final class PayosGateway {
    private static final String CREATE_PATH = "/v2/payment-requests";
    private static final String QUERY_PATH = "/v2/payment-requests/";
    private final String endpoint;
    private final String clientId;
    private final String apiKey;
    private final String checksumKey;
    private final Set<String> allowedRedirectHosts;
    private final Duration timeout;
    private final PayosTransport transport;

    public PayosGateway(String endpoint, String clientId, String apiKey, String checksumKey,
                        Set<String> allowedRedirectHosts, Duration timeout, PayosTransport transport) {
        this.endpoint = endpoint;
        this.clientId = clientId;
        this.apiKey = apiKey;
        this.checksumKey = checksumKey;
        this.allowedRedirectHosts = allowedRedirectHosts == null ? Set.of() : Set.copyOf(allowedRedirectHosts);
        this.timeout = timeout;
        this.transport = transport;
    }

    public boolean isConfigured() {
        return endpoint != null && endpoint.equals("https://api-merchant.payos.vn")
                && clientId != null && !clientId.isBlank() && apiKey != null && !apiKey.isBlank()
                && checksumKey != null && !checksumKey.isBlank() && !allowedRedirectHosts.isEmpty()
                && timeout != null && !timeout.isNegative() && !timeout.isZero() && transport != null;
    }

    public Result create(Command command) {
        if (!isConfigured()) return Result.rejected("PAYOS_NOT_CONFIGURED");
        final PayosProtocol.PaymentLinkRequest request;
        try {
            request = PayosProtocol.createPaymentRequest(command.orderCode(), command.amountVnd(), command.description(),
                    command.cancelUrl(), command.returnUrl(), command.expiresAt(), checksumKey);
            if (command.attemptId() == null) return Result.rejected("PAYMENT_ATTEMPT_REQUIRED");
        } catch (RuntimeException invalid) { return Result.rejected("PAYMENT_REQUEST_INVALID"); }
        try {
            PayosTransport.HttpResponse response = transport.createPaymentLink(endpoint + CREATE_PATH, clientId, apiKey, request.body(), timeout);
            if (response == null || response.status() < 200 || response.status() >= 300) return Result.unknown("PAYOS_CREATE_OUTCOME_UNKNOWN");
            Map<String, Object> data = PayosProtocol.parseCreateResponse(response.body(), allowedRedirectHosts,
                    command.orderCode(), command.amountVnd(), checksumKey);
            Instant expiresAt = command.expiresAt();
            return new Result(Outcome.ACCEPTED, command.orderCode(), command.amountVnd(),
                    String.valueOf(data.get("paymentLinkId")), String.valueOf(data.get("checkoutUrl")),
                    String.valueOf(data.get("qrCode")), text(data.get("bin")), text(data.get("accountNumber")),
                    text(data.get("accountName")), expiresAt,
                    PayosProtocol.verifiedCreateResponseDigest(response.body()), null);
        } catch (RuntimeException ambiguous) {
            return Result.unknown("PAYOS_CREATE_OUTCOME_UNKNOWN");
        }
    }

    private String text(Object value) { return value == null ? null : String.valueOf(value); }

    /** Query uses the persisted orderCode; it never creates a replacement payment link. */
    public ProviderPaymentState reconcile(PayosProtocol.ExpectedPayment expected) {
        if (!isConfigured() || expected == null) return ProviderPaymentState.UNKNOWN;
        try {
            PayosTransport.HttpResponse response = transport.getPaymentLink(endpoint + QUERY_PATH + expected.orderCode(),
                    clientId, apiKey, expected.orderCode(), timeout);
            if (response == null || response.status() < 200 || response.status() >= 300) return ProviderPaymentState.UNKNOWN;
            return PayosProtocol.verifyPaymentLinkQuery(response.body(), expected, checksumKey);
        } catch (RuntimeException unresolved) { return ProviderPaymentState.UNKNOWN; }
    }

    /** Returns opaque, signed query evidence; no Payment or Order state is changed here. */
    public PayosProtocol.VerifiedPaymentLinkQuery queryEvidence(String merchantChannelId, String queryRequestId,
            PayosProtocol.ExpectedPayment expected) {
        if (!isConfigured() || expected == null) return null;
        try {
            PayosTransport.HttpResponse response = transport.getPaymentLink(endpoint + QUERY_PATH + expected.orderCode(),
                    clientId, apiKey, expected.orderCode(), timeout);
            if (response == null || response.status() < 200 || response.status() >= 300) return null;
            return PayosProtocol.verifyPaymentLinkQueryEvidence(response.body(), merchantChannelId,
                    queryRequestId, expected, checksumKey);
        } catch (RuntimeException unresolved) { return null; }
    }

    public enum Outcome { ACCEPTED, REJECTED, UNKNOWN }
    public record Command(UUID attemptId, long orderCode, long amountVnd, String description,
                          String cancelUrl, String returnUrl, Instant expiresAt) {}
    public record Result(Outcome outcome, long orderCode, long amountVnd, String paymentLinkId,
                         String checkoutUrl, String qrCode, String bankBin, String recipientAccountNumber,
                         String accountName, Instant expiresAt, String providerResponseDigest, String errorCode) {
        static Result rejected(String code) { return new Result(Outcome.REJECTED, 0, 0, null, null, null,
                null, null, null, null, null, code); }
        static Result unknown(String code) { return new Result(Outcome.UNKNOWN, 0, 0, null, null, null,
                null, null, null, null, null, code); }
    }
}
